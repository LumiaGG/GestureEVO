package io.github.lumiagg.gestureevo.search

import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import io.github.lumiagg.gestureevo.gesture.GestureService
import io.github.lumiagg.gestureevo.utils.AccessibilityBlacklist
import kotlinx.coroutines.CancellationException
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

/** Waits for the target screen, then injects and verifies text within a bounded retry window. */
class AutomatePerformer(private val service: GestureService) {

    private enum class TaskState { IDLE, PENDING, RUNNING }

    private enum class AttemptResult { SUCCESS, NO_EDIT_BOX, RETRY, BLOCKED }

    @Volatile
    private var currentTask: InjectionTask? = null

    @Volatile
    private var taskState = TaskState.IDLE

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
        taskState = TaskState.PENDING

        // Start timeout monitor
        startTimeoutMonitor()
    }

    /**
     * Checks current foreground interface and executes injection if target is reached.
     */
    fun checkAndInject(currentPackage: String, currentActivity: String) {
        val task = currentTask ?: return
        if (taskState != TaskState.PENDING) return

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

        Log.i(TAG, "Target screen reached, starting injection")
        taskState = TaskState.RUNNING

        // Perform injection
        service.launch {
            try {
                // Brief delay ensuring view hierarchy is fully loaded
                delay(50)

                val result = performInjectionWithRetry(task)
                if (currentTask !== task) return@launch

                when (result) {
                    AttemptResult.SUCCESS -> completeTask()
                    AttemptResult.BLOCKED -> {
                        Log.i(TAG, "Injection blocked by the accessibility blocklist")
                        cancelCurrentTask()
                    }
                    else -> {
                        Log.e(TAG, "Injection failed after reaching maximum retry attempts")
                        cancelCurrentTask()
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (currentTask !== task) return@launch
                Log.e(TAG, "Exception during injection process", e)
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

    private suspend fun performInjectionWithRetry(task: InjectionTask): AttemptResult {
        repeat(MAX_INJECTION_ATTEMPTS) {
            if (currentTask !== task) return AttemptResult.RETRY

            val result = withTargetEditBox(task) { node -> inject(task, node) }
            when (result) {
                AttemptResult.BLOCKED -> return result
                AttemptResult.NO_EDIT_BOX -> delay(EDITBOX_SEARCH_INTERVAL_MS)
                AttemptResult.RETRY -> delay(RETRY_DELAY_MS)
                AttemptResult.SUCCESS -> {
                    delay(VERIFICATION_DELAY_MS)
                    if (currentTask !== task) return AttemptResult.RETRY
                    if (task.usePaste && task.query.isBlank()) return AttemptResult.SUCCESS

                    when (val verified = withTargetEditBox(task) { node -> verifyInjection(task, node) }) {
                        AttemptResult.SUCCESS, AttemptResult.BLOCKED -> return verified
                        else -> delay(RETRY_DELAY_MS)
                    }
                }
            }
        }
        return AttemptResult.RETRY
    }

    @Suppress("DEPRECATION")
    private suspend fun withTargetEditBox(
        task: InjectionTask,
        useNode: suspend (AccessibilityNodeInfoCompat) -> AttemptResult,
    ): AttemptResult = withContext(Dispatchers.Default) {
        val root = service.rootInActiveWindow ?: return@withContext AttemptResult.NO_EDIT_BOX
        val packageName = root.packageName?.toString()
        val unavailable = when {
            packageName != task.targetPackage -> AttemptResult.NO_EDIT_BOX
            AccessibilityBlacklist.isBlocked(packageName) -> AttemptResult.BLOCKED
            else -> null
        }
        if (unavailable != null) {
            root.recycle()
            return@withContext unavailable
        }

        val node = findEditBoxInTree(AccessibilityNodeInfoCompat.wrap(root), task.targetPackage)
            ?: return@withContext AttemptResult.NO_EDIT_BOX
        try {
            useNode(node)
        } finally {
            node.recycle()
        }
    }

    @Suppress("DEPRECATION")
    private fun findEditBoxInTree(
        rootNode: AccessibilityNodeInfoCompat,
        targetPackage: String,
    ): AccessibilityNodeInfoCompat? {
        // Breadth-first search prioritizing visible and editable nodes
        val queue = ArrayDeque<Pair<AccessibilityNodeInfoCompat, Int>>()
        queue.add(rootNode to 0)

        var best: AccessibilityNodeInfoCompat? = null
        var bestPriority = 0
        var completed = false
        try {
            while (queue.isNotEmpty()) {
                val (node, depth) = queue.removeFirst()
                try {
                    val packageName = node.packageName?.toString()
                    if (packageName != null && packageName != targetPackage) continue

                    if (isEditBox(node)) {
                        val priority = calculateNodePriority(node)
                        if (priority > bestPriority) {
                            best?.recycle()
                            best = node
                            bestPriority = priority
                        }
                    }

                    if (depth < MAX_TREE_DEPTH) {
                        for (i in 0 until node.childCount) {
                            node.getChild(i)?.let { queue.add(it to depth + 1) }
                        }
                    }
                } finally {
                    if (node !== best) node.recycle()
                }
            }
            completed = true
            return best
        } finally {
            if (!completed) best?.recycle()
            queue.forEach { (node, _) -> node.recycle() }
        }
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

    private suspend fun inject(task: InjectionTask, node: AccessibilityNodeInfoCompat): AttemptResult = try {
        withContext(Dispatchers.Main) {
            if (!node.isFocused) {
                val focus = performNodeAction(task, node, AccessibilityNodeInfoCompat.ACTION_FOCUS)
                if (focus == AttemptResult.BLOCKED) return@withContext focus
                delay(50)
            }

            if (task.usePaste) {
                return@withContext performNodeAction(task, node, AccessibilityNodeInfoCompat.ACTION_PASTE)
            }

            if (!node.text.isNullOrEmpty()) {
                val clear = setNodeText(task, node, "")
                if (clear == AttemptResult.BLOCKED) return@withContext clear
                delay(50)
            }
            setNodeText(task, node, task.query)
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.e(TAG, "Error injecting text", e)
        AttemptResult.RETRY
    }

    private fun setNodeText(
        task: InjectionTask,
        node: AccessibilityNodeInfoCompat,
        text: String,
    ): AttemptResult {
        val arguments = Bundle().apply {
            putCharSequence(AccessibilityNodeInfoCompat.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        return performNodeAction(task, node, AccessibilityNodeInfoCompat.ACTION_SET_TEXT, arguments)
    }

    private fun performNodeAction(
        task: InjectionTask,
        node: AccessibilityNodeInfoCompat,
        action: Int,
        arguments: Bundle? = null,
    ): AttemptResult {
        if (currentTask !== task || AccessibilityBlacklist.isBlocked(task.targetPackage)) {
            return AttemptResult.BLOCKED
        }
        return if (node.performAction(action, arguments)) AttemptResult.SUCCESS else AttemptResult.RETRY
    }

    private fun verifyInjection(task: InjectionTask, node: AccessibilityNodeInfoCompat): AttemptResult {
        val actual = node.text?.toString()?.trim().orEmpty()
        if (!actual.startsWith(task.query.trim())) return AttemptResult.RETRY

        if (task.inputEnter && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val submit = performNodeAction(task, node, AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id)
            if (submit == AttemptResult.BLOCKED) return submit
        }
        return AttemptResult.SUCCESS
    }

    /**
     * Starts timeout monitoring coroutine.
     */
    private fun startTimeoutMonitor() {
        monitorJob?.cancel()
        monitorJob = service.launch {
            delay(TASK_TIMEOUT_MS)

            when (taskState) {
                TaskState.PENDING -> {
                    Log.w(TAG, "Task timeout: target interface not reached")
                    cancelCurrentTask()
                }

                TaskState.RUNNING -> {
                    Log.w(TAG, "Task timeout: injection execution timed out")
                    cancelCurrentTask()
                }

                TaskState.IDLE -> Unit
            }
        }
    }

    /**
     * Marks task as completed and cancels monitor job.
     */
    private fun completeTask() {
        monitorJob?.cancel()
        currentTask = null
        taskState = TaskState.IDLE
        Log.i(TAG, "Task completed")
    }

    /**
     * Cancels the current task.
     */
    fun cancelCurrentTask() {
        monitorJob?.cancel()
        currentTask = null
        taskState = TaskState.IDLE
        Log.i(TAG, "Task cancelled")
    }

    /**
     * Checks whether a task is currently active.
     */
    fun isTaskActive(): Boolean {
        return taskState != TaskState.IDLE
    }
}
