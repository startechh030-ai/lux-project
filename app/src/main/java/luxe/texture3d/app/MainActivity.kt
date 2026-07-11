package luxe.texture3d.app

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.view.Choreographer
import android.view.Gravity
import android.view.SurfaceView
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import com.google.android.filament.Engine
import com.google.android.filament.EntityManager
import com.google.android.filament.LightManager
import com.google.android.filament.Skybox
import com.google.android.filament.utils.ModelViewer
import com.google.android.filament.utils.Utils
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer

class MainActivity : ComponentActivity(), Choreographer.FrameCallback {
    private lateinit var root: FrameLayout
    private lateinit var surfaceView: SurfaceView
    private lateinit var engine: Engine
    private lateinit var modelViewer: ModelViewer
    private lateinit var statusText: TextView
    private lateinit var axisGizmo: AxisGizmo
    private val modelOrbit = ModelOrbitController()
    private var lastFrameTimeNanos: Long = 0L
    private var baseModelTransform: FloatArray? = null

    private val pickGlb = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            result.data?.data?.let { uri -> loadGlbFromUri(uri) }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Utils.init()
        buildUi()
        setupFilament()
    }

    private fun buildUi() {
        root = FrameLayout(this)
        root.setBackgroundColor(Color.rgb(16, 18, 22))

        surfaceView = SurfaceView(this)
        root.addView(surfaceView, FrameLayout.LayoutParams(-1, -1))

        val chromeView = EditorChromeView(this).apply {
            isClickable = false
            isFocusable = false
        }
        root.addView(chromeView, FrameLayout.LayoutParams(-1, -1))

        // Dedicated transparent gesture layer. This is more reliable than attaching
        // touch directly to SurfaceView when Android overlays are above it.
        val gestureLayer = View(this).apply {
            setBackgroundColor(Color.TRANSPARENT)
            setOnTouchListener { _, event ->
                modelOrbit.onTouch(event)
                true
            }
        }
        root.addView(gestureLayer, FrameLayout.LayoutParams(-1, -1))

        val pivotDot = PivotDotView(this).apply {
            isClickable = false
            isFocusable = false
        }
        val dotSize = (24 * resources.displayMetrics.density).toInt()
        root.addView(pivotDot, FrameLayout.LayoutParams(dotSize, dotSize, Gravity.CENTER))

        val pickButton = ImageButton(this).apply {
            setImageResource(android.R.drawable.ic_menu_upload)
            setColorFilter(Color.WHITE)
            setBackgroundColor(Color.argb(230, 56, 189, 248))
            contentDescription = "Pick GLB file"
            setPadding(22, 22, 22, 22)
            setOnClickListener { openFilePicker() }
        }
        val buttonSize = (64 * resources.displayMetrics.density).toInt()
        val buttonParams = FrameLayout.LayoutParams(buttonSize, buttonSize, Gravity.TOP or Gravity.START).apply {
            topMargin = (24 * resources.displayMetrics.density).toInt()
            leftMargin = (18 * resources.displayMetrics.density).toInt()
        }
        root.addView(pickButton, buttonParams)

        axisGizmo = AxisGizmo(this)
        val gizmoSize = (118 * resources.displayMetrics.density).toInt()
        val gizmoParams = FrameLayout.LayoutParams(gizmoSize, gizmoSize, Gravity.TOP or Gravity.END).apply {
            topMargin = (14 * resources.displayMetrics.density).toInt()
            rightMargin = (14 * resources.displayMetrics.density).toInt()
        }
        root.addView(axisGizmo, gizmoParams)

        statusText = TextView(this).apply {
            text = "Tap the icon to pick a .glb file"
            setTextColor(Color.WHITE)
            textSize = 14f
            setPadding(16, 10, 16, 10)
            setBackgroundColor(Color.argb(185, 0, 0, 0))
        }
        root.addView(statusText, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM))

        setContentView(root)
    }

    private fun setupFilament() {
        engine = Engine.create()
        modelViewer = ModelViewer(surfaceView, engine)
        modelViewer.scene.skybox = Skybox.Builder()
            .color(0.075f, 0.083f, 0.105f, 1.0f)
            .build(engine)

        // Soft main light so imported GLBs do not appear flat/dark.
        val sun = EntityManager.get().create()
        LightManager.Builder(LightManager.Type.SUN)
            .color(1.0f, 0.96f, 0.88f)
            .intensity(95_000.0f)
            .direction(0.35f, -0.7f, -0.45f)
            .castShadows(true)
            .build(engine, sun)
        modelViewer.scene.addEntity(sun)
    }

    override fun onResume() {
        super.onResume()
        Choreographer.getInstance().postFrameCallback(this)
    }

    override fun onPause() {
        Choreographer.getInstance().removeFrameCallback(this)
        super.onPause()
    }

    override fun onDestroy() {
        super.onDestroy()
    }

    override fun doFrame(frameTimeNanos: Long) {
        if (::modelViewer.isInitialized && surfaceView.width > 0 && surfaceView.height > 0) {
            val delta = if (lastFrameTimeNanos == 0L) 1f / 60f
            else ((frameTimeNanos - lastFrameTimeNanos) / 1_000_000_000f).coerceIn(0.001f, 0.08f)
            lastFrameTimeNanos = frameTimeNanos

            val aspect = surfaceView.width.toDouble() / surfaceView.height.toDouble()
            applyStableCamera(aspect)
            applyModelOrbitTransform()
            updateGizmo()
            modelViewer.render(frameTimeNanos)
        }
        Choreographer.getInstance().postFrameCallback(this)
    }

    private fun applyStableCamera(aspect: Double) {
        // Camera stays static. Only the model root rotates/scales around the center pivot.
        modelViewer.camera.setProjection(45.0, aspect, 0.05, 1000.0, com.google.android.filament.Camera.Fov.VERTICAL)
        modelViewer.camera.lookAt(
            0.0, 0.0, 3.0,
            0.0, 0.0, 0.0,
            0.0, 1.0, 0.0
        )
    }

    private fun applyModelOrbitTransform() {
        val asset = modelViewer.asset ?: return
        val base = baseModelTransform ?: return
        val tm = engine.transformManager
        val instance = tm.getInstance(asset.root)
        val finalTransform = NativeModelTransform.multiplyColumnMajor(modelOrbit.transformMatrix(), base)
        tm.setTransform(instance, finalTransform)
    }

    private fun updateGizmo() {
        if (::axisGizmo.isInitialized) {
            axisGizmo.setCameraOrientation(modelOrbit.yaw, modelOrbit.pitch)
        }
    }

    private fun openFilePicker() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            putExtra(
                Intent.EXTRA_MIME_TYPES,
                arrayOf("model/gltf-binary", "model/gltf+json", "application/octet-stream")
            )
        }
        pickGlb.launch(intent)
    }

    private fun loadGlbFromUri(uri: Uri) {
        val name = displayName(uri) ?: "model.glb"
        statusText.text = "Loading $name ..."

        runCatching {
            val file = copyUriToCache(uri, name)
            val bytes = file.readBytes()
            val buffer = ByteBuffer.allocateDirect(bytes.size)
            buffer.put(bytes)
            buffer.rewind()

            modelViewer.destroyModel()
            modelViewer.loadModelGlb(buffer)
            modelViewer.transformToUnitCube()
            captureBaseModelTransform()
            modelOrbit.reset()
            lastFrameTimeNanos = 0L

            statusText.text = "Loaded: $name | 1 finger rotate model, pinch zoom. Camera stays fixed."
        }.onFailure { error ->
            statusText.text = "Failed to load GLB"
            Toast.makeText(this, error.message ?: "Unknown error", Toast.LENGTH_LONG).show()
        }
    }

    private fun captureBaseModelTransform() {
        val asset = modelViewer.asset ?: run {
            baseModelTransform = null
            return
        }
        val tm = engine.transformManager
        val instance = tm.getInstance(asset.root)
        baseModelTransform = FloatArray(16).also { base ->
            tm.getTransform(instance, base)
        }
    }

    private fun copyUriToCache(uri: Uri, fileName: String): File {
        val safeName = fileName.replace(Regex("[^A-Za-z0-9._-]"), "_").ifBlank { "model.glb" }
        val outFile = File(cacheDir, safeName)
        contentResolver.openInputStream(uri).use { input ->
            requireNotNull(input) { "Cannot open selected file" }
            FileOutputStream(outFile).use { output -> input.copyTo(output) }
        }
        return outFile
    }

    private fun displayName(uri: Uri): String? {
        return contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (cursor.moveToFirst() && index >= 0) cursor.getString(index) else null
        }
    }
}
