package luxe.texture3d.app

/**
 * Undo/redo for Edit mode.
 *
 * ## Model
 *
 * Because [MeshOperators] never mutates its input, an edit step is just a pair
 * (mesh, selection) captured *before* the operator ran. Undo is therefore a reference
 * swap — no inverse operators, no diffing, and no risk of an "undo" that fails to restore
 * something an operator forgot to invert.
 *
 * ## Memory
 *
 * [Entry] holds a full [EditMesh.clone]. At roughly 40 bytes per vertex (position, normal,
 * optional UV, plus index amortisation) a 200k-triangle mesh is around 8 MB per step, so
 * the default 24 steps can reach ~190 MB — over budget for a phone alongside a live
 * Filament scene.
 *
 * That is deliberately left as-is for the first iteration because correctness comes first
 * and the fix is local: replace the snapshot with per-operator attribute deltas (the
 * operator already knows exactly which vertices it touched) or store dirty-vertex
 * positions plus a topology log. Both are drop-in changes behind this class's API, so
 * nothing that calls [record] has to change.
 *
 * [memoryBytes] exists so the editor can surface the cost and prune when Android reports
 * memory pressure via `onTrimMemory`.
 */
class MeshHistory(private val limit: Int = 24) {

    /**
     * One recorded state. [label] names the operation that was applied *after* this state
     * was captured, so it reads naturally as "Undo Extrude".
     */
    class Entry(
        val label: String,
        val mesh: EditMesh,
        val selection: MeshSelection? = null
    )

    private val undoStack = ArrayDeque<Entry>()
    private val redoStack = ArrayDeque<Entry>()

    val canUndo: Boolean get() = undoStack.isNotEmpty()
    val canRedo: Boolean get() = redoStack.isNotEmpty()
    val undoLabel: String? get() = undoStack.lastOrNull()?.let { "Undo ${it.label}" }
    val redoLabel: String? get() = redoStack.lastOrNull()?.let { "Redo ${it.label}" }
    val depth: Int get() = undoStack.size

    /**
     * Captures the state **before** an operator runs. Call this first, then apply.
     *
     * Recording also invalidates the redo stack, matching every editor's behaviour:
     * making a new edit after an undo discards the redo branch.
     */
    fun record(label: String, mesh: EditMesh, selection: MeshSelection? = null) {
        undoStack.addLast(Entry(label, mesh.clone(), selection?.snapshot()))
        while (undoStack.size > limit) undoStack.removeFirst()
        redoStack.clear()
    }

    /**
     * Steps back one edit.
     *
     * @param current the mesh as it stands now; it is pushed onto the redo stack.
     * @return the state to restore, or null when there is nothing to undo.
     */
    fun undo(current: EditMesh, currentSelection: MeshSelection? = null): Entry? {
        val entry = undoStack.removeLastOrNull() ?: return null
        val label = entry.label
        redoStack.addLast(Entry(label, current.clone(), currentSelection?.snapshot()))
        return entry
    }

    /** Steps forward again after an [undo]. */
    fun redo(current: EditMesh, currentSelection: MeshSelection? = null): Entry? {
        val entry = redoStack.removeLastOrNull() ?: return null
        val label = entry.label
        undoStack.addLast(Entry(label, current.clone(), currentSelection?.snapshot()))
        return entry
    }

    fun clear() {
        undoStack.clear()
        redoStack.clear()
    }

    /** Rough retained size, for the editor's memory readout and pruning decisions. */
    fun memoryBytes(): Long {
        var total = 0L
        for (entry in undoStack) total += entry.mesh.memoryBytes()
        for (entry in redoStack) total += entry.mesh.memoryBytes()
        return total
    }
}

/** Retained size of a mesh's buffers, used by [MeshHistory.memoryBytes]. */
fun EditMesh.memoryBytes(): Long {
    var bytes = positions.size * 4L + indices.size * 4L
    normals?.let { bytes += it.size * 4L }
    uvs?.let { bytes += it.size * 4L }
    return bytes
}
