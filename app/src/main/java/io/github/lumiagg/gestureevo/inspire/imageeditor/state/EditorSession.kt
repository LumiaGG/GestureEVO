package io.github.lumiagg.gestureevo.inspire.imageeditor.state

import android.graphics.Color
import io.github.lumiagg.gestureevo.inspire.imageeditor.model.EditAction
import io.github.lumiagg.gestureevo.inspire.imageeditor.model.EditorMode
import io.github.lumiagg.gestureevo.inspire.imageeditor.model.ShapeType

data class EditorUiState(
    val currentMode: EditorMode = EditorMode.NAVIGATE,
    val currentColor: Int = Color.RED,
    val doodleStrokeWidth: Float = 16f,
    val eraserStrokeWidth: Float = 28f,
    val shapeStrokeWidth: Float = 18f,
    val mosaicStrokeWidth: Float = 42f,
    val currentShapeType: ShapeType = ShapeType.RECTANGLE,
    val undoStack: List<EditAction> = emptyList(),
    val redoStack: List<EditAction> = emptyList(),
) {
    val currentStrokeWidth: Float
        get() = brushWidthFor(currentMode)

    val visibleActions: List<EditAction>
        get() = undoStack

    val canUndo: Boolean
        get() = undoStack.isNotEmpty()

    val canRedo: Boolean
        get() = redoStack.isNotEmpty()

    fun brushWidthFor(mode: EditorMode): Float {
        return when (mode) {
            EditorMode.DOODLE -> doodleStrokeWidth
            EditorMode.ERASER -> eraserStrokeWidth
            EditorMode.SHAPE -> shapeStrokeWidth
            EditorMode.MOSAIC -> mosaicStrokeWidth
            EditorMode.NAVIGATE,
            EditorMode.CROP,
            EditorMode.TEXT -> doodleStrokeWidth
        }
    }
}

class EditorSession {

    fun interface Listener {
        fun onStateChanged(state: EditorUiState)
    }

    private val listeners = linkedSetOf<Listener>()

    var state: EditorUiState = EditorUiState()
        private set

    fun addListener(listener: Listener) {
        listeners += listener
        listener.onStateChanged(state)
    }

    fun removeListener(listener: Listener) {
        listeners -= listener
    }

    fun switchMode(mode: EditorMode) {
        updateState { it.copy(currentMode = mode) }
    }

    fun updateColor(color: Int) {
        updateState { it.copy(currentColor = color) }
    }

    fun updateStrokeWidth(strokeWidth: Float) {
        val targetWidth = strokeWidth.coerceAtLeast(1f)
        updateState { current ->
            when (current.currentMode) {
                EditorMode.DOODLE -> current.copy(doodleStrokeWidth = targetWidth)
                EditorMode.ERASER -> current.copy(eraserStrokeWidth = targetWidth)
                EditorMode.SHAPE -> current.copy(shapeStrokeWidth = targetWidth)
                EditorMode.MOSAIC -> current.copy(mosaicStrokeWidth = targetWidth)
                EditorMode.NAVIGATE,
                EditorMode.CROP,
                EditorMode.TEXT -> current
            }
        }
    }

    fun updateStrokeWidthForMode(mode: EditorMode, strokeWidth: Float) {
        val targetWidth = strokeWidth.coerceAtLeast(1f)
        updateState { current ->
            when (mode) {
                EditorMode.DOODLE -> current.copy(doodleStrokeWidth = targetWidth)
                EditorMode.ERASER -> current.copy(eraserStrokeWidth = targetWidth)
                EditorMode.SHAPE -> current.copy(shapeStrokeWidth = targetWidth)
                EditorMode.MOSAIC -> current.copy(mosaicStrokeWidth = targetWidth)
                EditorMode.NAVIGATE,
                EditorMode.CROP,
                EditorMode.TEXT -> current
            }
        }
    }

    fun updateShapeType(shapeType: ShapeType) {
        updateState { it.copy(currentShapeType = shapeType) }
    }

    fun addAction(action: EditAction) {
        updateState {
            it.copy(
                undoStack = it.undoStack + action,
                redoStack = emptyList(),
            )
        }
    }

    fun replaceActions(actions: List<EditAction>) {
        updateState {
            it.copy(
                undoStack = actions,
                redoStack = emptyList(),
            )
        }
    }

    fun updateTextAction(id: String, transform: (EditAction.Text) -> EditAction.Text) {
        updateState { current ->
            current.copy(
                undoStack = current.undoStack.map { action ->
                    if (action is EditAction.Text && action.id == id) {
                        transform(action)
                    } else {
                        action
                    }
                }
            )
        }
    }

    fun updateShapeAction(id: String, transform: (EditAction.Shape) -> EditAction.Shape) {
        updateState { current ->
            current.copy(
                undoStack = current.undoStack.map { action ->
                    if (action is EditAction.Shape && action.id == id) {
                        transform(action)
                    } else {
                        action
                    }
                }
            )
        }
    }

    fun removeAction(id: String) {
        updateState { current ->
            current.copy(
                undoStack = current.undoStack.filterNot { it.id == id },
                redoStack = current.redoStack.filterNot { it.id == id },
            )
        }
    }

    fun undo() {
        updateState { current ->
            if (current.undoStack.isEmpty()) {
                current
            } else {
                val lastAction = current.undoStack.last()
                current.copy(
                    undoStack = current.undoStack.dropLast(1),
                    redoStack = current.redoStack + lastAction,
                )
            }
        }
    }

    fun redo() {
        updateState { current ->
            if (current.redoStack.isEmpty()) {
                current
            } else {
                val nextAction = current.redoStack.last()
                current.copy(
                    undoStack = current.undoStack + nextAction,
                    redoStack = current.redoStack.dropLast(1),
                )
            }
        }
    }

    fun clearActions() {
        updateState { current ->
            current.copy(
                undoStack = emptyList(),
                redoStack = emptyList(),
            )
        }
    }

    private fun updateState(transform: (EditorUiState) -> EditorUiState) {
        state = transform(state)
        listeners.forEach { it.onStateChanged(state) }
    }
}
