package io.github.lumiagg.gestureevo.search

import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import io.github.lumiagg.gestureevo.gesture.GestureService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// Task configuration
data class InjectionTask(
    val query: String,
    val targetPackage: String,
    val targetActivity: String,
    val inputEnter: Boolean,
    val usePaste: Boolean = false,
    val startTime: Long = System.currentTimeMillis()
)

/**
 * Automated text injection performer.
 * Optimizes the injection pipeline for reliability and reduces false positives.
 */
class AutomatePerformer(private val service: GestureService) {

    // Task state
    private sealed class TaskState {
        object Idle : TaskState()
        data class Pending(val startTime: Long) : TaskState()
        data class Running(val startTime: Long) : TaskState()
        object Completed : TaskState()
        object Cancelled : TaskState()
    }


    @Volatile
    private var currentTask: InjectionTask? = null

    @Volatile
    private var taskState: TaskState = TaskState.Idle

    private var monitorJob: Job? = null

    // Configuration constants
    private companion object {
        const val TAG = "AutomatePerformer"

        // Timeout configurations
        const val TASK_TIMEOUT_MS = 15_000L

        // Retry configurations
        const val MAX_INJECTION_ATTEMPTS = 50
        const val RETRY_DELAY_MS = 80L
        const val VERIFICATION_DELAY_MS = 150L

        // Search configurations
        const val MAX_TREE_DEPTH = 25
        const val EDITBOX_SEARCH_INTERVAL_MS = 100L

        // Supported input edit box view classes
        val EDIT_BOX_CLASSES = setOf(
            "android.widget.EditText",
            "android.widget.AutoCompleteTextView",
            "androidx.appcompat.widget.AppCompatEditText",
            "androidx.appcompat.widget.SearchView\$SearchAutoComplete",
            "com.google.android.material.textfield.TextInputEditText",
            "ohos.agp.components.TextField"
        )
    }

    /**
     * Starts a text injection task.
     */
    fun startInjection(task: InjectionTask) {
        if ((!task.usePaste && task.query.isBlank()) ||
            task.targetPackage.isBlank()
        ) {
            Log.w(TAG, "startInjection: invalid arguments")
            return
        }

        // Cancel existing task
        cancelCurrentTask()

        // Create new task
        currentTask = task
        taskState = TaskState.Pending(System.currentTimeMillis())

        // Start timeout monitor
        startTimeoutMonitor()
    }

    /**
     * Checks current foreground interface and executes injection if target is reached.
     */
    fun checkAndInject(currentPackage: String, currentActivity: String) {
        val task = currentTask ?: return

        // Check task state
        if (taskState !is TaskState.Pending && taskState !is TaskState.Running) {
            return
        }

        Log.d(
            TAG,
            "Check screen - Current: $currentPackage/$currentActivity, Target: ${task.targetPackage}/${task.targetActivity}"
        )

        // Package name matching
        if (currentPackage != task.targetPackage) {
            return
        }

        // Activity matching (supports partial matching for dynamic/alias activities)
        if (task.targetActivity.isNotBlank() &&
            !isActivityMatched(currentActivity, task.targetActivity)
        ) {
            return
        }

        // Guard against duplicate execution
        if (taskState is TaskState.Running) {
            Log.d(TAG, "Task is already executing, skipping")
            return
        }

        Log.i(TAG, "Target screen reached, starting injection")
        taskState = TaskState.Running(System.currentTimeMillis())

        // Perform injection
        service.launch {
            try {
                // Brief delay ensuring view hierarchy is fully loaded
                delay(50)

                val success = performInjectionWithRetry(task.query)

                if (success) {
                    Log.i(TAG, "✓ Injection succeeded")
                    taskState = TaskState.Completed
                    completeTask()
                } else {
                    Log.e(TAG, "✗ Injection failed after reaching maximum retry attempts")
                    taskState = TaskState.Cancelled
                    cancelCurrentTask()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Exception during injection process", e)
                taskState = TaskState.Cancelled
                cancelCurrentTask()
            }
        }
    }

    /**
     * Determines whether the current activity matches the target activity (supports partial matching).
     */
    private fun isActivityMatched(current: String, target: String): Boolean {
        // Exact match
        if (current == target) return true

        // Contains match (handles activity alias or dynamic suffix)
        if (current.contains(target)) return true

        // Class name match ignoring package differences
        val currentClassName = current.substringAfterLast('.')
        val targetClassName = target.substringAfterLast('.')
        return currentClassName == targetClassName
    }

    /**
     * Executes injection and verifies result with retries.
     */
    private suspend fun performInjectionWithRetry(query: String): Boolean =
        withContext(Dispatchers.Default) {
            var attempts = 0

            while (attempts < MAX_INJECTION_ATTEMPTS) {
                attempts++

                // Check whether task has been cancelled
                if (taskState is TaskState.Cancelled) {
                    Log.d(TAG, "Task cancelled")
                    return@withContext false
                }

                // Find editable input node
                val editBox = findEditBoxInTree()
                if (editBox == null) {
                    Log.d(TAG, "Attempt $attempts: no editable node found")
                    delay(EDITBOX_SEARCH_INTERVAL_MS)
                    continue
                }

                // Perform injection
                val injected = if (currentTask?.usePaste == true) {
                    pasteToNode(editBox)
                } else {
                    injectTextToNode(editBox, query)
                }
                if (!injected) {
                    Log.d(TAG, "Attempt $attempts: injection operation failed")
                    delay(RETRY_DELAY_MS)
                    continue
                }

                // Verify injection result
                delay(VERIFICATION_DELAY_MS)

                val verified = if (currentTask?.usePaste == true) {
                    if (query.isBlank()) true else verifyInjection(query)
                } else {
                    verifyInjection(query)
                }
                if (verified) {
                    Log.i(TAG, "Injection verified successfully ($attempts attempts)")
                    return@withContext true
                } else {
                    Log.d(TAG, "Attempt $attempts: verification failed, content mismatch")
                    delay(RETRY_DELAY_MS)
                }
            }

            Log.e(TAG, "Reached maximum retry limit ($MAX_INJECTION_ATTEMPTS)")
            return@withContext false
        }

    /**
     * Searches for edit box node in accessibility tree.
     */
    private fun findEditBoxInTree(): AccessibilityNodeInfoCompat? {
        val rootNode = service.rootInActiveWindow?.let {
            AccessibilityNodeInfoCompat.wrap(it)
        } ?: return null

        val task = currentTask ?: return null

        // Breadth-first search prioritizing visible and editable nodes
        val queue = ArrayDeque<Pair<AccessibilityNodeInfoCompat, Int>>()
        queue.add(rootNode to 0)

        val candidates = mutableListOf<AccessibilityNodeInfoCompat>()

        while (queue.isNotEmpty()) {
            val (node, depth) = queue.removeFirst()

            // Depth limit
            if (depth > MAX_TREE_DEPTH) continue

            // Validate package name to prevent cross-app mismatch
            val nodePackage = node.unwrap()?.packageName?.toString()
            if (nodePackage != null && nodePackage != task.targetPackage) {
                continue
            }

            // Check whether node is an edit box
            if (isEditBox(node)) {
                // Filter by priority: visible > editable > focusable
                val priority = calculateNodePriority(node)
                if (priority > 0) {
                    candidates.add(node)
                }
            }

            // Add child nodes to search queue
            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { child ->
                    queue.add(child to depth + 1)
                }
            }
        }

        // Return candidate node with highest priority score
        return candidates.maxByOrNull { calculateNodePriority(it) }
    }

    /**
     * Determines whether a node is an editable text box.
     */
    private fun isEditBox(node: AccessibilityNodeInfoCompat?): Boolean {
        if (node == null) return false

        val className = node.className?.toString() ?: return false
        return EDIT_BOX_CLASSES.contains(className)
    }

    /**
     * Computes priority score for a node (higher score indicates higher relevance).
     */
    private fun calculateNodePriority(node: AccessibilityNodeInfoCompat): Int {
        var priority = 0

        // Must be editable
        if (!node.isEditable) return 0

        // Visibility (highest priority)
        if (node.isVisibleToUser) priority += 100

        // Enabled state
        if (node.isEnabled) priority += 50

        // Focusability
        if (node.isFocusable) priority += 30

        // Currently focused (likely the active input field)
        if (node.isFocused) priority += 20

        // Empty text content (avoids overwriting existing text)
        if (node.text.isNullOrEmpty()) priority += 10

        return priority
    }

    /**
     * Injects text into target accessibility node.
     */
    private suspend fun injectTextToNode(
        node: AccessibilityNodeInfoCompat,
        text: String
    ): Boolean = withContext(Dispatchers.Main) {
        try {
            // Attempt focusing first
            if (!node.isFocused) {
                node.performAction(AccessibilityNodeInfoCompat.ACTION_FOCUS)
                delay(50)
            }

            // Clear existing content
            if (!node.text.isNullOrEmpty()) {
                val clearBundle = Bundle().apply {
                    putCharSequence(
                        AccessibilityNodeInfoCompat.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                        ""
                    )
                }
                node.performAction(AccessibilityNodeInfoCompat.ACTION_SET_TEXT, clearBundle)
                delay(50)
            }

            // Inject text
            val setTextBundle = Bundle().apply {
                putCharSequence(
                    AccessibilityNodeInfoCompat.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                    text
                )
            }
            val setTextSuccess = node.performAction(
                AccessibilityNodeInfoCompat.ACTION_SET_TEXT,
                setTextBundle
            )

            if (!setTextSuccess) {
                Log.w(TAG, "ACTION_SET_TEXT returned false")
                return@withContext false
            }

            return@withContext true
        } catch (e: Exception) {
            Log.e(TAG, "Error injecting text", e)
            return@withContext false
        }
    }

    private suspend fun pasteToNode(node: AccessibilityNodeInfoCompat): Boolean =
        withContext(Dispatchers.Main) {
            try {
                if (!node.isFocused) {
                    node.performAction(AccessibilityNodeInfoCompat.ACTION_FOCUS)
                    delay(50)
                }
                val pasted = node.performAction(AccessibilityNodeInfoCompat.ACTION_PASTE)
                if (!pasted) {
                    Log.w(TAG, "ACTION_PASTE returned false")
                }
                return@withContext pasted
            } catch (e: Exception) {
                Log.e(TAG, "Error pasting text", e)
                return@withContext false
            }
        }

    /**
     * Verifies injection result against expected text.
     */
    private suspend fun verifyInjection(expectedText: String): Boolean =
        withContext(Dispatchers.Default) {
            val editBox = findEditBoxInTree() ?: return@withContext false

            val actualText = editBox.text?.toString()?.trim() ?: ""
            val expected = expectedText.trim()

            // Exact match or prefix match (accounting for auto-complete)
            if (actualText == expected || actualText.startsWith(expected)) {
                // Trigger IME submit action (Android R+)
                if (currentTask?.inputEnter == true && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    editBox.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id)
                }
                Log.d(TAG, "verifyInjection: trigger IME enter")
                return@withContext true
            }
            return@withContext false
        }

    /**
     * Starts timeout monitoring coroutine.
     */
    private fun startTimeoutMonitor() {
        monitorJob?.cancel()
        monitorJob = service.launch {
            delay(TASK_TIMEOUT_MS)

            when (val state = taskState) {
                is TaskState.Pending -> {
                    Log.w(TAG, "Task timeout: target interface not reached")
                    cancelCurrentTask()
                }

                is TaskState.Running -> {
                    Log.w(TAG, "Task timeout: injection execution timed out")
                    cancelCurrentTask()
                }

                else -> {
                    // Already completed or cancelled, no action needed
                }
            }
        }
    }

    /**
     * Marks task as completed and cancels monitor job.
     */
    private fun completeTask() {
        monitorJob?.cancel()
        currentTask = null
        taskState = TaskState.Completed
        Log.i(TAG, "Task completed")
    }

    /**
     * Cancels the current task.
     */
    fun cancelCurrentTask() {
        monitorJob?.cancel()
        currentTask = null
        taskState = TaskState.Cancelled
        Log.i(TAG, "Task cancelled")
    }

    /**
     * Checks whether a task is currently active.
     */
    fun isTaskActive(): Boolean {
        return taskState is TaskState.Pending || taskState is TaskState.Running
    }
}
