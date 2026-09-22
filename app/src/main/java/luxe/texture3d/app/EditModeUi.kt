package luxe.texture3d.app

import android.content.Context
import android.graphics.Color
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.google.android.filament.Engine
import com.google.android.filament.MaterialInstance
import com.google.android.filament.Scene
import java.io.File
import java.nio.ByteBuffer

/**
 * The Edit-mode UI: a compact vertical toolbar plus the glue that binds it to
 * [EditModeController].
 *
 * ## Layout choice
 *
 * The README records that the right side of the WIP editor "intentionally has no panel;
 * logic controls arrive with the MeshLibs implementation". This is that panel. It sits on
 * the right, is scrollable, and reuses the existing `hub_*` drawables and the same
 * 40-48 dp touch targets as the rest of the chrome, so it reads as part of Luxe rather
 * than as a bolted-on debug strip.
 *
 * ## Split
 *
 * - [EditModeToolbarView] is dumb: it renders buttons and reports presses. It knows
 *   nothing about meshes, so it can be restyled or replaced without touching the engine.
 * - [EditModeHost] is the integration layer: it owns the controller, enters and exits
 *   Edit mode, hides the gltfio asset while its mesh is being edited, converts taps into
 *   picking rays, and keeps the scene's dirty flag honest.
 *
 * Keeping that split is what makes the `EditorActivity` patch about a dozen lines.
 */
enum class EditOperator {
    SUBDIVIDE, EXTRUDE, INSET, DELETE, WELD,
    SELECT_ALL, INVERT, GROW, CLEAR, UNDO, REDO, EXIT,
    // Modelling tools. These are gesture driven rather than one-shot buttons: arming one
    // of them tells EditModeHost what a drag or a two-finger scroll should mean.
    MOVE, ROTATE, SCALE, LOOP_CUT, KNIFE, BEVEL, RELAB, MIRROR
}

/**
 * Renders the Edit-mode button column.
 *
 * ## DPI and screen size
 *
 * Every dimension goes through [dp], which folds in two things: the device density (so a
 * control is the same physical size everywhere) and a scale factor derived from the
 * screen's shorter side (so a phone and a tablet both get a layout that fits rather than a
 * strip of cramped buttons on one and a postage stamp on the other). Text scales with the
 * same factor, which keeps labels inside their buttons at every size.
 *
 * ## The name tip
 *
 * Icons alone are ambiguous, so tapping any tool slides its name out beside the column for
 * about a second. It is a plain tween rather than a `ViewPropertyAnimator` chain so it is
 * trivially cancellable: a rapid second tap restarts it instead of queueing.
 */
class EditModeToolbarView(context: Context) : LinearLayout(context) {

    /** Fired for any non-mode button. */
    var onOperator: (EditOperator) -> Unit = {}

    /** Fired when the artist switches vertex / edge / face. */
    var onElementMode: (ElementMode) -> Unit = {}

    private val modeButtons = LinkedHashMap<ElementMode, TextView>()
    private val operatorViews = LinkedHashMap<EditOperator, TextView>()

    /** dp -> px, scaled for the screen's size as well as its density. */
    private val density: Float = context.resources.displayMetrics.density
    private val sizeScale: Float = run {
        val metrics = context.resources.displayMetrics
        val shortestDp = minOf(metrics.widthPixels, metrics.heightPixels) / metrics.density
        (shortestDp / 360f).coerceIn(0.9f, 1.6f)
    }

    private val tip: TextView = TextView(context).apply {
        textSize = textDp(11f)
        setTextColor(Color.WHITE)
        gravity = Gravity.CENTER_VERTICAL or Gravity.END
        visibility = GONE
        alpha = 0f
    }

    init {
        orientation = VERTICAL
        setPadding(dp(8), dp(8), dp(8), dp(8))
        setBackgroundResource(R.drawable.panel_bg)

        addRow(listOf(
            modeButton("Vert", ElementMode.VERTEX),
            modeButton("Edge", ElementMode.EDGE),
            modeButton("Face", ElementMode.FACE)
        ))
        addRow(listOf(
            toolButton("Move", EditOperator.MOVE),
            toolButton("Rotate", EditOperator.ROTATE),
            toolButton("Scale", EditOperator.SCALE)
        ))
        addRow(listOf(
            toolButton("Loop", EditOperator.LOOP_CUT),
            toolButton("Knife", EditOperator.KNIFE),
            toolButton("Bevel", EditOperator.BEVEL)
        ))
        addRow(listOf(
            toolButton("Relab", EditOperator.RELAB),
            toolButton("Mirror", EditOperator.MIRROR),
            operatorButton("Subdiv", EditOperator.SUBDIVIDE)
        ))
        addRow(listOf(
            operatorButton("Extrude", EditOperator.EXTRUDE),
            operatorButton("Inset", EditOperator.INSET),
            operatorButton("Delete", EditOperator.DELETE)
        ))
        addRow(listOf(
            operatorButton("Weld", EditOperator.WELD),
            operatorButton("All", EditOperator.SELECT_ALL),
            operatorButton("Invert", EditOperator.INVERT)
        ))
        addRow(listOf(
            operatorButton("Grow", EditOperator.GROW),
            operatorButton("None", EditOperator.CLEAR),
            operatorButton("Undo", EditOperator.UNDO)
        ))
        addRow(listOf(
            operatorButton("Redo", EditOperator.REDO),
            operatorButton("Exit", EditOperator.EXIT)
        ))

        addView(tip, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(20)).also {
            it.topMargin = dp(2)
        })
    }

    private fun addRow(buttons: List<TextView>) {
        val row = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER
        }
        for (i in buttons.indices) {
            val params = LinearLayout.LayoutParams(0, dp(BUTTON_HEIGHT_DP), 1f)
            if (i > 0) params.leftMargin = dp(4)
            row.addView(buttons[i], params)
        }
        addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(BUTTON_HEIGHT_DP + 6)))
    }

    private fun button(label: String, background: Int, action: () -> Unit): TextView =
        TextView(context).apply {
            text = label
            textSize = textDp(11f)
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            setBackgroundResource(background)
            setOnClickListener { action() }
        }

    private fun operatorButton(label: String, operator: EditOperator): TextView =
        button(label, R.drawable.hub_secondary_button) {
            onOperator(operator)
            showName(nameOf(operator))
        }.also { operatorViews[operator] = it }

    private fun toolButton(label: String, operator: EditOperator): TextView =
        button(label, R.drawable.hub_secondary_button) {
            onOperator(operator)
            showName(nameOf(operator))
        }.also { operatorViews[operator] = it }

    private fun modeButton(label: String, mode: ElementMode): TextView =
        button(label, R.drawable.hub_secondary_button) { onElementMode(mode) }.also {
            modeButtons[mode] = it
        }

    /**
     * Highlights the active element mode and the armed tool, and reflects what the current
     * selection allows. Disabling a button that would be a no-op is better feedback than
     * letting the artist press it and see nothing happen.
     */
    fun updateState(
        mode: ElementMode,
        hasSelection: Boolean,
        canUndo: Boolean,
        canRedo: Boolean,
        armedTool: EditOperator? = null
    ) {
        for ((elementMode, view) in modeButtons) {
            view.setBackgroundResource(
                if (elementMode == mode) R.drawable.hub_primary_button
                else R.drawable.hub_secondary_button
            )
        }
        for ((operator, view) in operatorViews) {
            view.setBackgroundResource(
                if (operator == armedTool) R.drawable.hub_primary_button
                else R.drawable.hub_secondary_button
            )
        }
        setEnabledFor(EditOperator.SUBDIVIDE, true)
        setEnabledFor(EditOperator.EXTRUDE, hasSelection)
        setEnabledFor(EditOperator.INSET, hasSelection)
        setEnabledFor(EditOperator.DELETE, hasSelection)
        setEnabledFor(EditOperator.GROW, hasSelection)
        setEnabledFor(EditOperator.MOVE, hasSelection)
        setEnabledFor(EditOperator.ROTATE, hasSelection)
        setEnabledFor(EditOperator.SCALE, hasSelection)
        setEnabledFor(EditOperator.BEVEL, hasSelection)
        setEnabledFor(EditOperator.RELAB, hasSelection)
        setEnabledFor(EditOperator.MIRROR, true)
        setEnabledFor(EditOperator.LOOP_CUT, true)
        setEnabledFor(EditOperator.KNIFE, true)
        setEnabledFor(EditOperator.UNDO, canUndo)
        setEnabledFor(EditOperator.REDO, canRedo)
    }

    private fun setEnabledFor(operator: EditOperator, enabled: Boolean) {
        operatorViews[operator]?.let { view ->
            view.isEnabled = enabled
            view.alpha = if (enabled) 1f else 0.38f
        }
    }

    // ------------------------------------------------------------- name tip

    /** Slides [name] out beside the column for roughly a second. */
    fun showName(name: String) {
        tip.text = name
        tip.visibility = VISIBLE
        tipRunnable?.let { removeCallbacks(it) }
        var step = 0
        val runner = object : Runnable {
            override fun run() {
                step++
                val progress = step.toFloat() / TIP_STEPS
                val fadeIn = (progress / 0.18f).coerceIn(0f, 1f)
                val fadeOut = ((1f - progress) / 0.32f).coerceIn(0f, 1f)
                tip.alpha = minOf(fadeIn, fadeOut)
                tip.translationX = dp(14) * (1f - fadeIn)
                if (step < TIP_STEPS) postDelayed(this, TIP_FRAME_MS)
                else tip.visibility = GONE
            }
        }
        tipRunnable = runner
        post(runner)
    }

    private var tipRunnable: Runnable? = null

    companion object {
        private const val BUTTON_HEIGHT_DP = 40
        private const val TIP_STEPS = 12
        private const val TIP_FRAME_MS = 80L

        /** Long labels for the name tip, since the buttons only have room for short ones. */
        fun nameOf(operator: EditOperator): String = when (operator) {
            EditOperator.SUBDIVIDE -> "Subdivide"
            EditOperator.EXTRUDE -> "Extrude"
            EditOperator.INSET -> "Inset"
            EditOperator.DELETE -> "Delete"
            EditOperator.WELD -> "Weld"
            EditOperator.SELECT_ALL -> "Select all"
            EditOperator.INVERT -> "Invert selection"
            EditOperator.GROW -> "Grow selection"
            EditOperator.CLEAR -> "Select none"
            EditOperator.UNDO -> "Undo"
            EditOperator.REDO -> "Redo"
            EditOperator.EXIT -> "Leave edit mode"
            EditOperator.MOVE -> "Move — drag"
            EditOperator.ROTATE -> "Rotate — drag"
            EditOperator.SCALE -> "Scale — drag"
            EditOperator.LOOP_CUT -> "Loop cut — tap an edge, then drag"
            EditOperator.KNIFE -> "Knife — drag a cut line"
            EditOperator.BEVEL -> "Bevel — tap an edge, hold, then drag"
            EditOperator.RELAB -> "Mesh relab — select faces, then scroll"
            EditOperator.MIRROR -> "Mirror — duplicate and flip"
        }
    }

    private fun dp(value: Int): Int = (value * sizeScale * density).toInt()

    private fun textDp(value: Float): Float = value * sizeScale
}

/**
 * The transform panel: the counterweight to the toolbar.
 *
 * It sits on the opposite side of the screen from [EditModeToolbarView] so the two never
 * fight for the same thumb, and it only appears while a transform tool is armed.
 *
 * Scaling is uniform by default, which is what almost every move wants. Turning uniform off
 * exposes X / Y / Z, and picking one locks the drag to that axis; picking it again releases
 * the lock back to "all".
 */
class EditModeTransformPanel(context: Context) : LinearLayout(context) {

    /** Fired when uniform scaling is switched on or off. */
    var onUniformChanged: (Boolean) -> Unit = {}

    /** Fired when the axis lock changes: -1 = all, 0/1/2 = X/Y/Z. */
    var onAxisLockChanged: (Int) -> Unit = {}

    /** Whether scaling currently applies to all three axes at once. */
    var uniform: Boolean = true
        private set

    /** -1 for every axis, otherwise the locked axis index. Resets whenever uniform is on. */
    var axisLock: Int = -1
        private set

    private val density: Float = context.resources.displayMetrics.density
    private val sizeScale: Float = run {
        val metrics = context.resources.displayMetrics
        val shortestDp = minOf(metrics.widthPixels, metrics.heightPixels) / metrics.density
        (shortestDp / 360f).coerceIn(0.9f, 1.6f)
    }

    private val uniformButton: TextView
    private val axisButtons = ArrayList<TextView>()

    init {
        orientation = VERTICAL
        setPadding(dp(8), dp(8), dp(8), dp(8))
        setBackgroundResource(R.drawable.panel_bg)

        addView(TextView(context).apply {
            text = "Transform"
            textSize = 11f * sizeScale
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
        }, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(22)))

        uniformButton = button("Uniform: on") {
            uniform = !uniform
            if (uniform) axisLock = -1
            refresh()
            onUniformChanged(uniform)
            onAxisLockChanged(axisLock)
        }
        addView(uniformButton, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(32)).also {
            it.topMargin = dp(4)
        })

        val row = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER
        }
        for (label in listOf("X", "Y", "Z")) {
            val index = axisButtons.size
            val axisButton = button(label) {
                axisLock = if (axisLock == index) -1 else index
                refresh()
                onAxisLockChanged(axisLock)
            }
            axisButtons += axisButton
            val params = LinearLayout.LayoutParams(0, dp(32), 1f)
            if (index > 0) params.leftMargin = dp(4)
            row.addView(axisButton, params)
        }
        addView(row, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(34)).also { it.topMargin = dp(4) })
        refresh()
    }

    /** Shows or hides the axis row according to whether uniform is on. */
    private fun refresh() {
        uniformButton.text = if (uniform) "Uniform: on" else "Uniform: off"
        uniformButton.alpha = 1f
        for (index in axisButtons.indices) {
            val locked = index == axisLock
            axisButtons[index].alpha = if (uniform) 0.38f else if (locked) 1f else 0.7f
            axisButtons[index].isEnabled = !uniform
            axisButtons[index].setBackgroundResource(
                if (locked) R.drawable.hub_primary_button else R.drawable.hub_secondary_button
            )
        }
    }

    private fun button(label: String, action: () -> Unit): TextView =
        TextView(context).apply {
            text = label
            textSize = 11f * sizeScale
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            setBackgroundResource(R.drawable.hub_secondary_button)
            setOnClickListener { action() }
        }

    private fun dp(value: Int): Int = (value * sizeScale * density).toInt()
}

/**
 * Binds Edit mode to the running editor.
 *
 * ## The asset-hiding detail
 *
 * While an instance is being edited, `EditableMeshRenderer` draws its own renderable. The
 * original gltfio asset is still in the scene, so without [hideOriginalAsset] the viewport
 * shows both the untouched model and the edited copy at the same position — a z-fighting
 * mess that looks like a renderer bug. Entering Edit mode therefore hides the source
 * asset and exiting restores it.
 */
class EditModeHost(
    private val context: Context,
    engine: Engine,
    scene: Scene,
    lineMaterialBuffer: ByteBuffer,
    surfaceMaterialProvider: () -> MaterialInstance,
    private val sceneManager: EditorSceneManager,
    private val onDirty: () -> Unit = {},
    private val onStatus: (String) -> Unit = {}
) {
    /** Camera basis plus viewport, supplied per-tap so picking always uses live state. */
    class CameraFrame(
        val eye: FloatArray,
        val target: FloatArray,
        val up: FloatArray,
        val width: Int,
        val height: Int,
        val tanHalfFovY: Float
    )

    /** Supplies the current camera. Set by `EditorActivity`, which owns the Manipulator. */
    var cameraProvider: () -> CameraFrame? = { null }

    val toolbar: EditModeToolbarView = EditModeToolbarView(context)

    /**
     * The transform panel. It lives on the opposite side of the screen from [toolbar] so the
     * two never compete for the same thumb; `EditorActivity` places it.
     */
    val transformPanel: EditModeTransformPanel = EditModeTransformPanel(context).apply {
        visibility = View.GONE
    }

    val controller = EditModeController(
        engine = engine,
        scene = scene,
        lineMaterialBuffer = lineMaterialBuffer,
        surfaceMaterialProvider = surfaceMaterialProvider,
        onSelectionChanged = { onStatus(it) },
        onGeometryChanged = { onDirty() }
    )

    val active: Boolean get() = controller.active

    /** UID of the instance being edited, or null. */
    val editingUid: String? get() = controller.instanceUid

    private var hiddenUid: String? = null

    init {
        toolbar.onElementMode = { mode ->
            controller.setElementMode(mode)
            refreshToolbar()
        }
        toolbar.onOperator = { operator -> runOperator(operator) }
        toolbar.visibility = View.GONE
    }

    // ------------------------------------------------------------- lifecycle

    /**
     * Enters Edit mode for [uid].
     *
     * @param sessionDir project session directory, used to resolve relative asset sources
     * @return false when the instance is missing, locked, or its geometry cannot be read
     */
    fun enter(uid: String, sessionDir: File?): Boolean {
        if (controller.active) exit()
        val record = sceneManager.all().firstOrNull { it.uid == uid } ?: run {
            toast("That object is no longer in the scene")
            return false
        }
        if (record.locked) {
            toast("Unlock this object before editing it")
            return false
        }
        if (!controller.enter(record, sessionDir)) {
            toast("Could not read this model's geometry for editing")
            return false
        }
        hideOriginalAsset(uid)
        toolbar.visibility = View.VISIBLE
        refreshToolbar()
        onStatus(controller.stats())
        controller.loadWarnings.firstOrNull()?.let { toast(it) }
        return true
    }

    /**
     * Leaves Edit mode and, unless [discardEdits] is set, writes the edited geometry to
     * `<sessionDir>/local/<uid>-edited.glb`.
     *
     * Without that write, Edit mode would be a dead end: the mesh lives only in memory and
     * the artist's work would disappear the moment they left. The GLB is written in the
     * same shape the import pipeline already understands, so it can be picked up again.
     *
     * Phase 6C should stop treating this as a side file and instead point the scene
     * instance's source at it, so the edit becomes simply part of the project.
     */
    fun exit(discardEdits: Boolean = false) {
        if (!controller.active) return
        val uid = controller.instanceUid
        val edited = controller.mesh
        controller.exit()
        restoreOriginalAsset()
        toolbar.visibility = View.GONE
        onStatus("")

        if (discardEdits || uid == null) return
        val directory = sessionDir
        if (directory == null) {
            onEditedMeshAvailable?.invoke(uid, edited)
            onDirty()
            return
        }
        val target = File(directory, "local/$uid-edited.glb")
        runCatching { MeshGltfWriter.writeGlb(target, edited, uid) }
            .onSuccess { file ->
                onStatus("Saved ${file.name}")
                onEditedMeshSaved?.invoke(uid, file)
                onDirty()
            }
            .onFailure { error ->
                toast("Could not save edits: ${error.message}")
                onEditedMeshAvailable?.invoke(uid, edited)
                onDirty()
            }
    }

    /** Set so the host can persist edited geometry. Usually the project session directory. */
    var sessionDir: File? = null

    /** Called after the edited geometry has been written to disk. */
    var onEditedMeshSaved: ((uid: String, file: File) -> Unit)? = null

    /** Called when geometry was edited but could not be persisted. */
    var onEditedMeshAvailable: ((uid: String, mesh: EditMesh) -> Unit)? = null

    // --------------------------------------------------------------- picking

    /**
     * Handles a viewport tap in Edit mode.
     *
     * @return true when the tap was consumed as a selection. When false the caller should
     *   fall back to its normal object picking, which keeps object mode working unchanged.
     */
    fun handleTap(x: Float, y: Float, additive: Boolean = false): Boolean {
        if (!controller.active) return false
        val frame = cameraProvider() ?: return false
        val ray = MeshRaycast.rayFromScreen(
            frame.eye, frame.target, frame.up,
            x, y, frame.width, frame.height, frame.tanHalfFovY
        )
        controller.pick(
            originWorld = floatArrayOf(ray[0], ray[1], ray[2]),
            directionWorld = floatArrayOf(ray[3], ray[4], ray[5]),
            additive = additive
        )
        refreshToolbar()
        onStatus(controller.stats())
        return true
    }

    // --------------------------------------------------------------- gestures
    //
    // The modelling tools are gesture driven, mirroring how they work on a desktop: arm the
    // tool, commit the element you mean with a long press, then drag to place it and scroll
    // with two fingers to add more. Keeping this in the host rather than the view means the
    // toolbar stays a dumb column of buttons.

    /**
     * The tool a drag or two-finger scroll will drive, or null when none is armed.
     *
     * Arming a tool does not change the mesh; it only decides what the next gesture means.
     */
    var armedTool: EditOperator? = null
        private set

    /** Element committed by a long press, for tools that act on one picked edge or face. */
    private var armedElement = -1

    /** Live gesture parameters, driven by the drag and the scroll. */
    private var loopPosition = 0.5f
    private var loopCount = 1
    private var bevelSegments = 1
    private var relabRings = 1
    private var knifeStart = FloatArray(2)
    private var knifeEnd = FloatArray(2)
    private var knifeActive = false

    /** Arms [tool], or disarms when null. */
    fun armTool(tool: EditOperator?) {
        armedTool = tool
        loopCount = 1
        bevelSegments = 1
        relabRings = 1
        knifeActive = false
        armedElement = -1
        transformPanel.visibility = when (tool) {
            EditOperator.MOVE, EditOperator.ROTATE, EditOperator.SCALE -> View.VISIBLE
            else -> View.GONE
        }
        toolbar.showName(
            tool?.let { EditModeToolbarView.nameOf(it) } ?: "Tool disarmed"
        )
        refreshToolbar()
    }

    /**
     * Commits the element under the finger for the armed tool — the "click the edge you
     * want, then hold" step.
     *
     * @return true when an element was committed
     */
    fun handleLongPress(x: Float, y: Float): Boolean {
        if (!controller.active) return false
        if (!handleTap(x, y)) return false
        armedElement = controller.lastPick
        if (armedElement < 0) return false
        toolbar.showName(
            "${EditModeToolbarView.nameOf(armedTool ?: return false)} — committed"
        )
        return true
    }

    /**
     * One-finger drag, in pixels, while a tool is armed.
     *
     * Transforms follow the camera, so a drag always moves the selection the way the artist
     * sees it on screen: right is screen-right, up is screen-up, regardless of how the
     * model is oriented.
     */
    fun handleDrag(x: Float, y: Float, dxPx: Float, dyPx: Float): Boolean {
        val tool = armedTool ?: return false
        if (!controller.active) return false
        when (tool) {
            EditOperator.MOVE -> dragMove(dxPx, dyPx)
            EditOperator.ROTATE -> dragRotate(dxPx, dyPx)
            EditOperator.SCALE -> dragScale(dxPx, dyPx)
            EditOperator.LOOP_CUT -> {
                loopPosition = (loopPosition + dxPx / 240f).coerceIn(0.05f, 0.95f)
                previewLoopCut()
            }
            EditOperator.KNIFE -> {
                // The knife needs where the line was drawn, not how far the finger moved.
                if (!knifeActive) { knifeStart = floatArrayOf(x, y); knifeActive = true }
                knifeEnd = floatArrayOf(x, y)
            }
            EditOperator.BEVEL -> { /* committed on release; the drag only sets the width */ }
            EditOperator.RELAB -> { /* driven by the two-finger scroll instead */ }
            else -> return false
        }
        return true
    }

    /** Lifts the finger: this is where the one-shot tools actually run. */
    fun handleDragEnd() {
        val tool = armedTool ?: return
        if (!controller.active) return
        when (tool) {
            EditOperator.MOVE, EditOperator.ROTATE, EditOperator.SCALE ->
                controller.endGesture(EditModeToolbarView.nameOf(tool))
            EditOperator.LOOP_CUT -> {
                if (armedElement >= 0) controller.loopCut(armedElement, loopPosition, loopCount)
                controller.endGesture("Loop cut")
            }
            EditOperator.KNIFE -> commitKnife()
            EditOperator.BEVEL -> {
                if (armedElement >= 0 || controller.selection.edges.isNotEmpty()) {
                    controller.bevel(segments = bevelSegments, width = bevelWidth)
                }
                controller.endGesture("Bevel")
            }
            EditOperator.RELAB -> {
                controller.relab(relabRings)
                controller.endGesture("Mesh relab")
            }
            else -> Unit
        }
        knifeActive = false
        refreshToolbar()
        onStatus(controller.stats())
    }

    /**
     * Two-finger scroll: the "add more cuts" gesture.
     *
     * Scrolling up adds, scrolling down removes, clamped to a sane range so a stray flick
     * cannot ask for a hundred segments.
     */
    fun handleTwoFingerScroll(scrollAmount: Float): Boolean {
        val tool = armedTool ?: return false
        if (!controller.active) return false
        val steps = (scrollAmount / SCROLL_STEP_PX).toInt()
        if (steps == 0) return false
        when (tool) {
            EditOperator.BEVEL -> {
                bevelSegments = (bevelSegments + steps).coerceIn(1, 8)
                onStatus("Bevel: $bevelSegments segment${if (bevelSegments == 1) "" else "s"}")
            }
            EditOperator.LOOP_CUT -> {
                loopCount = (loopCount + steps).coerceIn(1, 32)
                previewLoopCut()
                onStatus("Loop cut: $loopCount loop${if (loopCount == 1) "" else "s"}")
            }
            EditOperator.RELAB -> {
                relabRings = (relabRings + steps).coerceIn(1, 16)
                onStatus("Mesh relab: $relabRings ring${if (relabRings == 1) "" else "s"}")
            }
            else -> return false
        }
        return true
    }

    /** Begins a live gesture so intermediate frames do not each become an undo step. */
    fun beginGesture() = controller.beginGesture()

    private fun previewLoopCut() {
        if (armedElement < 0) return
        controller.preview(
            MeshOperators.loopCut(controller.mesh, controller.topology, armedElement, loopPosition, loopCount).mesh
        )
    }

    /**
     * Cuts along the dragged line.
     *
     * A screen-space line plus the camera position defines a plane in space, and that plane
     * is the cut. Using the eye rather than a fixed axis is what makes the knife follow the
     * line the artist actually drew, from whatever angle they are looking at it.
     */
    private fun commitKnife() {
        val frame = cameraProvider() ?: return
        if (!knifeActive) return
        val normal = knifePlaneNormal(frame, knifeStart[0], knifeStart[1], knifeEnd[0], knifeEnd[1])
            ?: return
        val point = knifeStartPoint(frame, knifeStart[0], knifeStart[1]) ?: return
        controller.knife(point, normal)
        controller.endGesture("Knife")
    }

    private fun knifeStartPoint(frame: CameraFrame, x: Float, y: Float): FloatArray? {
        val ray = MeshRaycast.rayFromScreen(
            frame.eye, frame.target, frame.up, x, y, frame.width, frame.height, frame.tanHalfFovY
        )
        val local = MeshRaycast.toLocalRay(controller.worldMatrix,
            floatArrayOf(ray[0], ray[1], ray[2]), floatArrayOf(ray[3], ray[4], ray[5])) ?: return null
        return floatArrayOf(local[0], local[1], local[2])
    }

    private fun knifePlaneNormal(
        frame: CameraFrame, x0: Float, y0: Float, x1: Float, y1: Float
    ): FloatArray? {
        val a = knifeStartPoint(frame, x0, y0) ?: return null
        val b = knifeStartPoint(frame, x1, y1) ?: return null
        // Plane through the eye and the two ends of the drawn line.
        val u = floatArrayOf(a[0] - frame.eye[0], a[1] - frame.eye[1], a[2] - frame.eye[2])
        val v = floatArrayOf(b[0] - frame.eye[0], b[1] - frame.eye[1], b[2] - frame.eye[2])
        val n = floatArrayOf(
            u[1] * v[2] - u[2] * v[1],
            u[2] * v[0] - u[0] * v[2],
            u[0] * v[1] - u[1] * v[0]
        )
        if (MeshMath.length(n[0], n[1], n[2]) < 1e-6f) return null
        MeshMath.normalize(n)
        return n
    }

    /** World units per screen pixel at the orbit target — the scale a drag uses. */
    private fun worldPerPixel(frame: CameraFrame): Float {
        val distance = MeshMath.distance(
            frame.eye[0], frame.eye[1], frame.eye[2],
            frame.target[0], frame.target[1], frame.target[2]
        )
        return 2f * frame.tanHalfFovY * distance / frame.height.coerceAtLeast(1)
    }

    /** Screen-right and screen-up in world space, plus the view direction. */
    private fun cameraBasis(frame: CameraFrame): Triple<FloatArray, FloatArray, FloatArray> {
        val forward = floatArrayOf(
            frame.target[0] - frame.eye[0],
            frame.target[1] - frame.eye[1],
            frame.target[2] - frame.eye[2]
        )
        MeshMath.normalize(forward)
        val up = frame.up.copyOf()
        MeshMath.normalize(up)
        val right = floatArrayOf(
            forward[1] * up[2] - forward[2] * up[1],
            forward[2] * up[0] - forward[0] * up[2],
            forward[0] * up[1] - forward[1] * up[0]
        )
        MeshMath.normalize(right)
        val trueUp = floatArrayOf(
            right[1] * forward[2] - right[2] * forward[1],
            right[2] * forward[0] - right[0] * forward[2],
            right[0] * forward[1] - right[1] * forward[0]
        )
        MeshMath.normalize(trueUp)
        return Triple(right, trueUp, forward)
    }

    private fun dragMove(dxPx: Float, dyPx: Float) {
        val frame = cameraProvider() ?: return
        val (right, up, _) = cameraBasis(frame)
        val k = worldPerPixel(frame)
        val dx = (right[0] * dxPx - up[0] * dyPx) * k
        val dy = (right[1] * dxPx - up[1] * dyPx) * k
        val dz = (right[2] * dxPx - up[2] * dyPx) * k
        controller.translateSelected(dx, dy, dz)
    }

    private fun dragRotate(dxPx: Float, dyPx: Float) {
        val frame = cameraProvider() ?: return
        val (right, up, _) = cameraBasis(frame)
        // Drag right spins about screen-up; drag up spins about screen-right.
        val angleX = dxPx * ROTATE_PER_PX
        val angleY = -dyPx * ROTATE_PER_PX
        controller.rotateSelected(up[0], up[1], up[2], angleX)
        controller.rotateSelected(right[0], right[1], right[2], angleY)
    }

    private fun dragScale(dxPx: Float, dyPx: Float) {
        val amount = 1f - dyPx * SCALE_PER_PX
        val factor = amount.coerceIn(0.05f, 20f)
        val lock = if (transformPanel.uniform) -1 else transformPanel.axisLock
        val sx = if (lock == -1 || lock == 0) factor else 1f
        val sy = if (lock == -1 || lock == 1) factor else 1f
        val sz = if (lock == -1 || lock == 2) factor else 1f
        controller.scaleSelected(sx, sy, sz)
    }

    // ------------------------------------------------------------- operators

    private fun runOperator(operator: EditOperator) {
        when (operator) {
            EditOperator.EXIT -> exit()
            // Modelling tools arm a gesture rather than doing something on the spot.
            EditOperator.MOVE, EditOperator.ROTATE, EditOperator.SCALE,
            EditOperator.LOOP_CUT, EditOperator.KNIFE, EditOperator.BEVEL,
            EditOperator.RELAB -> armTool(if (armedTool == operator) null else operator)
            EditOperator.MIRROR -> controller.mirror(MeshOperators.Axis.X)
            EditOperator.SUBDIVIDE -> controller.subdivide(MeshOperators.Scheme.LOOP, 1)
            EditOperator.EXTRUDE -> controller.extrude(extrudeDistance)
            EditOperator.INSET -> controller.inset(insetAmount)
            EditOperator.DELETE -> controller.deleteSelected()
            EditOperator.WELD -> controller.weld()
            EditOperator.SELECT_ALL -> controller.selectAll()
            EditOperator.INVERT -> controller.invertSelection()
            EditOperator.GROW -> controller.growSelection()
            EditOperator.CLEAR -> controller.clearSelection()
            EditOperator.UNDO -> controller.undo()
            EditOperator.REDO -> controller.redo()
        }
        refreshToolbar()
        onStatus(controller.stats())
    }

    /** Operator parameters. Exposed so the WIP chrome's numeric fields can drive them. */
    var extrudeDistance = 0.25f
    var insetAmount = 0.25f

    /** Bevel width as a fraction of the shortest edge at either end of the beveled edge. */
    var bevelWidth = 0.25f

    companion object {
        /** Pixels of two-finger scroll per added segment, loop or ring. */
        private const val SCROLL_STEP_PX = 48f
        private const val ROTATE_PER_PX = 0.01f
        private const val SCALE_PER_PX = 0.006f
    }

    private fun refreshToolbar() {
        toolbar.updateState(
            mode = controller.selection.mode,
            hasSelection = !controller.selection.isEmpty(),
            canUndo = controller.history.canUndo,
            canRedo = controller.history.canRedo,
            armedTool = armedTool
        )
    }

    // -------------------------------------------------------------- internals

    private fun hideOriginalAsset(uid: String) {
        hiddenUid = uid
        sceneManager.setVisible(uid, false)
    }

    private fun restoreOriginalAsset() {
        hiddenUid?.let { sceneManager.setVisible(it, true) }
        hiddenUid = null
    }

    private fun toast(message: String) {
        Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
    }

    fun destroy() {
        if (controller.active) exit(discardEdits = true)
        controller.destroy()
    }
}
