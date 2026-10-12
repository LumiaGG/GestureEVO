package io.github.lumiagg.gestureevo.gesture.config

import android.content.Context
import android.util.Log
import io.github.lumiagg.gestureevo.R
import io.github.lumiagg.gestureevo.utils.gestureStore
import io.github.lumiagg.gestureevo.utils.readKey
import io.github.lumiagg.gestureevo.utils.saveKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.UUID

object FreeTriggerDataManager {
    private const val TAG = "FreeTriggerManager"
    private const val KEY_FREE_TRIGGERS = "key_free_triggers"

    // Mutex to ensure atomic operations
    private val mutex = Mutex()

    // In-memory cache to reduce read operations
    private var cachedTriggers: List<FreeTriggerConfig>? = null
    private var cacheValid = false

    fun buildTag(): String {
        return UUID.randomUUID().toString()
    }

    /**
     * Creates default trigger configuration.
     */
    fun createDefaultTrigger(context: Context): FreeTriggerConfig {
        return FreeTriggerConfig().apply {
            tag = buildTag()
            name = context.getString(R.string.default_trigger_name)
            setState(FreeTriggerConfig.FLAG_PORTRAIT, true)
            setState(FreeTriggerConfig.FLAG_LANDSCAPE, true)
            setState(FreeTriggerConfig.FLAG_MIRROR, true)
            xRelativeValue = 0f
            yRelativeValue = 1f
        }
    }

    /**
     * Ensures at least one trigger exists, creating a default one if list is empty.
     * @return true if default trigger was added, false otherwise.
     */
    suspend fun ensureDefaultTrigger(context: Context): Boolean =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                val triggers = if (cacheValid && cachedTriggers != null) {
                    cachedTriggers!!
                } else {
                    val loaded = FreeTriggerConfig.toModelList(
                        context.gestureStore.readKey(KEY_FREE_TRIGGERS, "")
                    ) ?: ArrayList()
                    cachedTriggers = loaded
                    cacheValid = true
                    loaded
                }

                if (triggers.isEmpty()) {
                    val defaultTrigger = createDefaultTrigger(context)
                    val updatedTriggers = ArrayList<FreeTriggerConfig>().apply {
                        add(defaultTrigger)
                    }
                    saveTriggersInternal(context, updatedTriggers)
                    return@withLock true
                }

                return@withLock false
            }
        }

    /**
     * Reads trigger list and ensures at least one default trigger exists.
     */
    suspend fun readTriggers(context: Context): List<FreeTriggerConfig> =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                val triggers = if (cacheValid && cachedTriggers != null) {
                    ArrayList(cachedTriggers!!)
                } else {
                    val loaded = FreeTriggerConfig.toModelList(
                        context.gestureStore.readKey(KEY_FREE_TRIGGERS, "")
                    ) ?: ArrayList()
                    cachedTriggers = loaded
                    cacheValid = true
                    ArrayList(loaded)
                }

                if (triggers.isEmpty()) {
                    val defaultTrigger = createDefaultTrigger(context)
                    triggers.add(defaultTrigger)
                    saveTriggersInternal(context, triggers)
                }
                triggers.forEach {
                    it.updateGestureActionList(context)
                }
                return@withContext triggers
            }
        }

    private suspend fun saveTriggersInternal(
        context: Context,
        triggers: List<FreeTriggerConfig>
    ) {
        val json = FreeTriggerConfig.toJsonList(triggers).also {
            Log.d(TAG, "saveTriggers: size=${triggers.size}")
        }

        // Update cache
        cachedTriggers = ArrayList(triggers)
        cacheValid = true

        // Write to persistent storage
        context.gestureStore.saveKey(KEY_FREE_TRIGGERS, json)
    }

    suspend fun saveTriggers(context: Context, triggers: List<FreeTriggerConfig>) =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                saveTriggersInternal(context, triggers)
            }
        }

    suspend fun addTrigger(context: Context, trigger: FreeTriggerConfig) =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                val triggers = ArrayList(
                    if (cacheValid && cachedTriggers != null) {
                        cachedTriggers!!
                    } else {
                        val loaded = FreeTriggerConfig.toModelList(
                            context.gestureStore.readKey(KEY_FREE_TRIGGERS, "")
                        ) ?: ArrayList()
                        cachedTriggers = loaded
                        cacheValid = true
                        loaded
                    }
                )

                trigger.tag = buildTag()
                triggers.add(trigger)
                saveTriggersInternal(context, triggers)

                return@withLock trigger.tag
            }
        }

    suspend fun updateTrigger(context: Context, trigger: FreeTriggerConfig): Boolean =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                val triggers = ArrayList(
                    if (cacheValid && cachedTriggers != null) {
                        cachedTriggers!!
                    } else {
                        val loaded = FreeTriggerConfig.toModelList(
                            context.gestureStore.readKey(KEY_FREE_TRIGGERS, "")
                        ) ?: ArrayList()
                        cachedTriggers = loaded
                        cacheValid = true
                        loaded
                    }
                )

                val index = triggers.indexOfFirst { it.tag == trigger.tag }
                if (index != -1) {
                    triggers[index] = trigger
                    saveTriggersInternal(context, triggers)
                    true
                } else {
                    Log.w(TAG, "updateTrigger: Trigger not found with tag ${trigger.tag}")
                    false
                }
            }
        }

    suspend fun removeTrigger(context: Context, tag: String): Boolean =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                Log.d(TAG, "removeTrigger: $tag")
                val triggers = ArrayList(
                    if (cacheValid && cachedTriggers != null) {
                        cachedTriggers!!
                    } else {
                        val loaded = FreeTriggerConfig.toModelList(
                            context.gestureStore.readKey(KEY_FREE_TRIGGERS, "")
                        ) ?: ArrayList()
                        cachedTriggers = loaded
                        cacheValid = true
                        loaded
                    }
                )

                val removed = triggers.removeIf { it.tag == tag }
                if (removed) {
                    // If list is empty after deletion, add a default trigger
                    if (triggers.isEmpty()) {
                        triggers.add(createDefaultTrigger(context))
                    }
                    saveTriggersInternal(context, triggers)
                }
                removed
            }
        }

    suspend fun getTrigger(context: Context, tag: String): FreeTriggerConfig? =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                val triggers = if (cacheValid && cachedTriggers != null) {
                    cachedTriggers!!
                } else {
                    val loaded = FreeTriggerConfig.toModelList(
                        context.gestureStore.readKey(KEY_FREE_TRIGGERS, "")
                    ) ?: ArrayList()
                    cachedTriggers = loaded
                    cacheValid = true
                    loaded
                }

                triggers.find { it.tag == tag }
            }
        }

    suspend fun clearAllTriggers(context: Context) =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                // Add a default trigger after clearing all triggers
                val defaultTrigger = createDefaultTrigger(context)
                saveTriggersInternal(context, listOf(defaultTrigger))
            }
        }

    // Force-refreshes cache when external storage changes occur
    suspend fun invalidateCache() {
        mutex.withLock {
            cacheValid = false
            cachedTriggers = null
        }
    }

    // Batch operations - allows multiple changes as a single atomic transaction
    suspend fun batchOperation(
        context: Context,
        operation: suspend (List<FreeTriggerConfig>) -> List<FreeTriggerConfig>
    ): Boolean =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                val triggers = ArrayList(
                    if (cacheValid && cachedTriggers != null) {
                        cachedTriggers!!
                    } else {
                        val loaded = FreeTriggerConfig.toModelList(
                            context.gestureStore.readKey(KEY_FREE_TRIGGERS, "")
                        ) ?: ArrayList()
                        cachedTriggers = loaded
                        cacheValid = true
                        loaded
                    }
                )

                try {
                    val updatedTriggers = operation(triggers)
                    // Ensure at least one trigger remains after batch operation
                    val finalTriggers = if (updatedTriggers.isEmpty()) {
                        listOf(createDefaultTrigger(context))
                    } else {
                        updatedTriggers
                    }
                    saveTriggersInternal(context, finalTriggers)
                    true
                } catch (e: Exception) {
                    Log.e(TAG, "Error during batch operation", e)
                    false
                }
            }
        }
}
