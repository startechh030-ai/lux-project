package com.arena.simpleglbviewer

import android.animation.ValueAnimator
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.view.*
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.animation.doOnEnd
import androidx.core.content.ContextCompat
import androidx.core.view.*
import com.google.android.filament.*
import com.google.android.filament.utils.*
import com.google.android.material.button.MaterialButton
import kotlinx.coroutines.*
import java.io.File
import java.nio.ByteBuffer
import java.nio.charset.Charset
import kotlin.math.*

class MainActivity : AppCompatActivity() {

    companion object {
        init { Utils.init() }
        private const val TAG = "GLBViewer"
        private const val DOUBLE_TAP_TIMEOUT = 300L
    }

    private lateinit var surfaceView: SurfaceView
    private lateinit var choreographer: Choreographer
    private lateinit var modelViewer: ModelViewer
    private val uiHelper: UiHelper = UiHelper(UiHelper.ContextErrorPolicy.DONT_CHECK)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private lateinit var btnPick: MaterialButton
    private lateinit var progressBar: ProgressBar
    private lateinit var tvFileName: TextView
    private lateinit var tvVertexCount: TextView
    private lateinit var tvTriCount: TextView
    private lateinit var tvMaterialCount: TextView
    private lateinit var tvFileSize: TextView
    private lateinit var tvAnimCount: TextView
    private lateinit var tvNodeCount: TextView
    private lateinit var tvCameraInfo: TextView
    private lateinit var tvGizmoLabel: TextView
    private lateinit var btnReset: MaterialButton
    private lateinit var btnToggleGrid: MaterialButton
    private lateinit var btnToggleWireframe: MaterialButton
    private lateinit var btnToggleCulling: MaterialButton
    private lateinit var btnToggleAutoRotate: MaterialButton
    private lateinit var axisGizmoView: AxisGizmoView
    private lateinit var editorChrome: EditorChromeView
    private lateinit var gestureHandler: NativeCameraGestureHandler

    private var currentFileName: String = "No file loaded"
    private var currentFileSize: Long = 0L
    private var isGridVisible = true
    private var isWireframe = false
    private var isCullingEnabled = true
    private var isAutoRotating = false
    private var isLoading = false
    private var lastTapTime = 0L
    private var tapCount = 0
    private var doubleTapJob: Job? = null

    private val frameScheduler = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            choreographer.postFrameCallback(this)
            if (isLoading) return
            modelViewer.render(frameTimeNanos)
            doFrame()
        }
    }

    private val filePicker = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let { loadGlb(it) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContentView(R.layout.activity_main)

        initViews()
        setupSurfaceView()
        setupButtons()
        setupGestureHandler()
        setupEditorChrome()
        setupAxisGizmo()
        setupSystemBars()
        setupInitialState()

        choreographer = Choreographer.getInstance()
        choreographer.postFrameCallback(frameScheduler)
    }

    private fun initViews() {
        surfaceView = findViewById(R.id.surfaceView)
        btnPick = findViewById(R.id.btnPick)
        progressBar = findViewById(R.id.progressBar)
        tvFileName = findViewById(R.id.tvFileName)
        tvVertexCount = findViewById(R.id.tvVertexCount)
        tvTriCount = findViewById(R.id.tvTriCount)
        tvMaterialCount = findViewById(R.id.tvMaterialCount)
        tvFileSize = findViewById(R.id.tvFileSize)
        tvAnimCount = findViewById(R.id.tvAnimCount)
        tvNodeCount = findViewById(R.id.tvNodeCount)
        tvCameraInfo = findViewById(R.id.tvCameraInfo)
        tvGizmoLabel = findViewById(R.id.tvGizmoLabel)
        btnReset = findViewById(R.id.btnReset)
        btnToggleGrid = findViewById(R.id.btnToggleGrid)
        btnToggleWireframe = findViewById(R.id.btnToggleWireframe)
        btnToggleCulling = findViewById(R.id.btnToggleCulling)
        btnToggleAutoRotate = findViewById(R.id.btnToggleAutoRotate)
        axisGizmoView = findViewById(R.id.axisGizmoView)
        editorChrome = findViewById(R.id.editorChrome)
    }

    private fun setupSurfaceView() {
        surfaceView.setOnTouchListener { _, event ->
            gestureHandler.onTouchEvent(event)
            true
        }
        surfaceView.setZOrderOnTop(false)
    }

    private fun setupGestureHandler() {
        gestureHandler = NativeCameraGestureHandler(this)
    }

    private fun setupButtons() {
        btnPick.setOnClickListener { filePicker.launch("*/*") }
        btnReset.setOnClickListener {
            NativeCamera.resetToDefault()
            updateButtonStates()
        }
        btnToggleGrid.setOnClickListener {
            isGridVisible = !isGridVisible
            updateButtonStates()
        }
        btnToggleWireframe.setOnClickListener {
            isWireframe = !isWireframe
            modelViewer.view.blendMode = if (isWireframe) View.BlendMode.TRANSLUCENT else View.BlendMode.OPAQUE
            updateButtonStates()
        }
        btnToggleCulling.setOnClickListener {
            isCullingEnabled = !isCullingEnabled
            modelViewer.view.isFrontFaceWindingInverted = !isCullingEnabled
            updateButtonStates()
        }
        btnToggleAutoRotate.setOnClickListener {
            NativeCamera.toggleAutoRotate()
            isAutoRotating = NativeCamera.isAutoRotating()
            updateButtonStates()
        }
    }

    private fun setupEditorChrome() {
        editorChrome.setOnActionListener { action ->
            when (action) {
                EditorChromeView.Action.IMPORT -> filePicker.launch("*/*")
                EditorChromeView.Action.RESET -> {
                    NativeCamera.resetToDefault()
                    updateButtonStates()
                }
                EditorChromeView.Action.TOGGLE_GRID -> {
                    isGridVisible = !isGridVisible
                    updateButtonStates()
                }
                EditorChromeView.Action.TOGGLE_WIREFRAME -> {
                    isWireframe = !isWireframe
                    modelViewer.view.blendMode = if (isWireframe) View.BlendMode.TRANSLUCENT else View.BlendMode.OPAQUE
                    updateButtonStates()
                }
                EditorChromeView.Action.TOGGLE_CULLING -> {
                    isCullingEnabled = !isCullingEnabled
                    modelViewer.view.isFrontFaceWindingInverted = !isCullingEnabled
                    updateButtonStates()
                }
                EditorChromeView.Action.TOGGLE_AUTO_ROTATE -> {
                    NativeCamera.toggleAutoRotate()
                    isAutoRotating = NativeCamera.isAutoRotating()
                    updateButtonStates()
                }
            }
        }
    }

    private fun setupAxisGizmo() {
        axisGizmoView.setOnGizmoClickListener { axis ->
            when (axis) {
                AxisGizmoView.Axis.X -> {
                    NativeCamera.setTarget(0f, 0f, 0f)
                    // Front view (looking down -X)
                }
                AxisGizmoView.Axis.Y -> {
                    NativeCamera.setTarget(0f, 0f, 0f)
                    // Top view
                }
                AxisGizmoView.Axis.Z -> {
                    NativeCamera.setTarget(0f, 0f, 0f)
                    // Side view
                }
            }
        }
    }

    private fun setupSystemBars() {
        WindowInsetsControllerCompat(window, window.decorView).let { controller ->
            controller.hide(WindowInsetsCompat.Type.statusBars())
            controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    private fun setupInitialState() {
        NativeCamera.init()
        NativeCamera.setScreenSize(surfaceView.width.coerceAtLeast(1080), surfaceView.height.coerceAtLeast(1920))
        NativeCamera.resetToDefault()
        updateButtonStates()
        tvFileName.text = "Tap 'Import GLB' to load a model"
    }

    private fun updateButtonStates() {
        btnToggleGrid.text = if (isGridVisible) "Hide Grid" else "Show Grid"
        btnToggleWireframe.text = if (isWireframe) "Solid" else "Wireframe"
        btnToggleCulling.text = if (isCullingEnabled) "Disable Culling" else "Enable Culling"
        btnToggleAutoRotate.text = if (isAutoRotating) "Stop Rotate" else "Auto Rotate"
    }

    private fun loadGlb(uri: Uri) {
        if (isLoading) return
        isLoading = true
        progressBar.visibility = View.VISIBLE
        tvFileName.text = "Loading..."

        scope.launch(Dispatchers.IO) {
            try {
                val buffer = readAssetToBuffer(uri)
                val fileName = getFileName(uri)
                currentFileName = fileName
                currentFileSize = buffer.remaining().toLong()

                withContext(Dispatchers.Main) {
                    modelViewer.loadModelGlb(buffer)
                    modelViewer.transformToUnitCube()

                    // Get model bounds and frame camera
                    val asset = modelViewer.asset
                    if (asset != null) {
                        val bbox = asset.boundingBox
                        val min = bbox.min
                        val max = bbox.max
                        val cx = (min[0] + max[0]) * 0.5f
                        val cy = (min[1] + max[1]) * 0.5f
                        val cz = (min[2] + max[2]) * 0.5f
                        val dx = max[0] - min[0]
                        val dy = max[1] - min[1]
                        val dz = max[2] - min[2]
                        val radius = sqrt(dx*dx + dy*dy + dz*dz) * 0.5f

                        NativeCamera.frameModel(min[0], min[1], min[2], max[0], max[1], max[2])
                        updateModelInfo(asset)
                    }

                    tvFileName.text = fileName
                    isLoading = false
                    progressBar.visibility = View.GONE
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    tvFileName.text = "Error: ${e.message}"
                    isLoading = false
                    progressBar.visibility = View.GONE
                }
            }
        }
    }

    private fun readAssetToBuffer(uri: Uri): ByteBuffer {
        contentResolver.openInputStream(uri)?.use { input ->
            val bytes = input.readBytes()
            return ByteBuffer.allocateDirect(bytes.size).apply {
                put(bytes)
                flip()
            }
        } ?: throw IllegalStateException("Could not open URI")
    }

    private fun getFileName(uri: Uri): String {
        var result = "unknown.glb"
        contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (nameIndex >= 0) result = cursor.getString(nameIndex)
            }
        }
        return result
    }

    private fun updateModelInfo(asset: FilamentAsset) {
        val totalVerts = asset.renderableEntityCount
        var triCount = 0
        var matCount = 0
        val mats = mutableSetOf<Int>()

        repeat(asset.renderableEntityCount) { i ->
            val entity = asset.getRenderableEntity(i)
            val manager = modelViewer.engine.renderableManager
            val instance = manager.getInstance(entity)
            if (instance != 0) {
                repeat(manager.getPrimitiveCount(instance)) { prim ->
                    triCount += manager.getIndexBuffer(instance)?.let { ib ->
                        ib.indexCount / 3
                    } ?: 0
                }
                val mat = manager.getMaterialInstanceAt(instance, 0)
                if (mat != null) mats.add(mat.hashCode())
            }
        }

        tvVertexCount.text = "Vertices: ${totalVerts}"
        tvTriCount.text = "Triangles: ${triCount}"
        tvMaterialCount.text = "Materials: ${mats.size}"
        tvFileSize.text = "Size: ${formatFileSize(currentFileSize)}"
        tvAnimCount.text = "Animations: ${asset.animator.animationCount}"
        tvNodeCount.text = "Nodes: ${asset.entities.size}"
    }

    private fun formatFileSize(size: Long): String {
        return when {
            size >= 1024 * 1024 -> "%.2f MB".format(size / (1024.0 * 1024.0))
            size >= 1024 -> "%.2f KB".format(size / 1024.0)
            else -> "$size B"
        }
    }

    // =========================================================================
    // KEY FIX: Use native camera position for Filament camera, NOT model rotation
    // =========================================================================
    private fun doFrame() {
        if (isLoading) return

        // Update native camera (handles inertia, smoothing, auto-rotate)
        NativeCamera.update(0.016f)

        // Get computed camera position from native
        val camX = NativeCamera.getCamX()
        val camY = NativeCamera.getCamY()
        val camZ = NativeCamera.getCamZ()
        val targetX = NativeCamera.getTargetX()
        val targetY = NativeCamera.getTargetY()
        val targetZ = NativeCamera.getTargetZ()

        // Apply to Filament camera (THIS IS THE REAL CAMERA ORBIT)
        val camera = modelViewer.camera
        val eye = Float3(camX, camY, camZ)
        val target = Float3(targetX, targetY, targetZ)
        val up = Float3(0f, 1f, 0f)
        camera.lookAt(eye.x, eye.y, eye.z, target.x, target.y, target.z, up.x, up.y, up.z)

        // Update UI
        updateCameraInfo()
        updateAxisGizmo()
        updateEditorChrome()
    }

    private fun updateCameraInfo() {
        val yaw = Math.toDegrees(NativeCamera.getYaw().toDouble())
        val pitch = Math.toDegrees(NativeCamera.getPitch().toDouble())
        val dist = NativeCamera.getDistance()
        val pitchClamped = ((pitch % 360 + 360) % 360).toFloat()
        val yawClamped = ((yaw % 360 + 360) % 360).toFloat()
        tvCameraInfo.text = "Yaw: ${"%.1f".format(yawClamped)}° | Pitch: ${"%.1f".format(pitchClamped)}° | Dist: ${"%.2f".format(dist)}"
    }

    private fun updateAxisGizmo() {
        val yaw = NativeCamera.getYaw()
        val pitch = NativeCamera.getPitch()
        axisGizmoView.updateRotation(yaw, pitch)
    }

    private fun updateEditorChrome() {
        val yaw = NativeCamera.getYaw()
        val pitch = NativeCamera.getPitch()
        val dist = NativeCamera.getDistance()
        editorChrome.updateCameraInfo(yaw, pitch, dist)
    }

    override fun onResume() {
        super.onResume()
        choreographer.postFrameCallback(frameScheduler)
    }

    override fun onPause() {
        super.onPause()
        choreographer.removeFrameCallback(frameScheduler)
    }

    override fun onDestroy() {
        super.onDestroy()
        choreographer.removeFrameCallback(frameScheduler)
        scope.cancel()
        NativeCamera.destroy()
    }

    data class Float3(val x: Float, val y: Float, val z: Float)
}
