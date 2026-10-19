package com.teamuhi.superbcamera.client.ui

import com.teamuhi.superbcamera.client.CameraController
import com.teamuhi.superbcamera.client.Perspective
import net.minecraft.Util

/**
 * Undo / redo of the perspective being edited: a capped stack of [Perspective] snapshots (copies made with
 * [Perspective.copyFrom]). A snapshot is pushed at the start of each gesture, modal operation, field edit, toggle or
 * anchor change. The stacks belong to one perspective instance and are cleared when another one is edited.
 *
 * The name is not part of the history (it is renamed through its own field), so restoring a snapshot keeps the
 * current name.
 */
class EditHistory {

    private val undoStack = ArrayDeque<Perspective>()
    private val redoStack = ArrayDeque<Perspective>()
    private var owner: Perspective? = null
    private var lastKey: Any? = null
    private var lastPushMillis = 0L

    val canUndo: Boolean get() = undoStack.isNotEmpty()
    val canRedo: Boolean get() = redoStack.isNotEmpty()

    /** Forgets everything (another perspective is being edited). */
    fun clear() {
        undoStack.clear()
        redoStack.clear()
        owner = null
        lastKey = null
    }

    /**
     * Records the state of [target] as it is now, before a change. Pushes with the same non-null [key] within
     * [COALESCE_MS] are folded into one step (wheel notches on a number field).
     */
    fun push(target: Perspective?, key: Any? = null) {
        if (target == null) return
        pushSnapshot(target, copyOf(target), key)
    }

    /** Records an already taken [snapshot] of [target] (a modal operation pushes its start state when confirmed). */
    fun pushSnapshot(target: Perspective, snapshot: Perspective, key: Any? = null) {
        bind(target)
        val now = Util.getMillis()
        if (key != null && key === lastKey && now - lastPushMillis < COALESCE_MS) {
            lastPushMillis = now
            return
        }
        lastKey = key
        lastPushMillis = now
        undoStack.addLast(snapshot)
        while (undoStack.size > MAX_STEPS) undoStack.removeFirst()
        redoStack.clear()
    }

    /** Restores the previous state into [target]. Returns false when there is nothing to undo. */
    fun undo(target: Perspective): Boolean {
        bind(target)
        val previous = undoStack.removeLastOrNull() ?: return false
        redoStack.addLast(copyOf(target))
        restore(target, previous)
        return true
    }

    /** Re-applies the state that was undone. Returns false when there is nothing to redo. */
    fun redo(target: Perspective): Boolean {
        bind(target)
        val next = redoStack.removeLastOrNull() ?: return false
        undoStack.addLast(copyOf(target))
        restore(target, next)
        return true
    }

    /** Writes the saved fields of [snapshot] into [target], keeping its name, and snaps the camera to them. */
    fun restore(target: Perspective, snapshot: Perspective) {
        val name = target.name
        target.copyFrom(snapshot)
        target.name = name
        lastKey = null
        CameraController.resetSmoothing()
    }

    /** A detached copy of [target]'s state. */
    fun copyOf(target: Perspective): Perspective = Perspective().also { it.copyFrom(target) }

    private fun bind(target: Perspective) {
        if (owner !== target) {
            undoStack.clear()
            redoStack.clear()
            lastKey = null
            owner = target
        }
    }

    private companion object {
        const val MAX_STEPS = 64
        const val COALESCE_MS = 600L
    }
}
