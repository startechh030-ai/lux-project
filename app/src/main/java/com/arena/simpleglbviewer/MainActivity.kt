package com.arena.simpleglbviewer

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.view.Choreographer
import android.view.Gravity
import android.view.SurfaceView
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
    private lateinit var axisGizmoView: AxisGizmoView
    private lateinit var gestureHandler: NativeCameraGestureHandler
    private var lastFrameTimeNanos: Long = 0L

    private val pickGlb = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            result.data?.data?.let { uri -> loadGlbFromUri(uri) }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Utils.init()
        NativeCamera.init()
        gestureHandler = NativeCameraGestureHandler(this)
        buildUi()
        setupFilament()
    }

    private fun buildUi() {
        root = FrameLayout(this)
        root.setBackgroundColor(Color.rgb(16, 18, 22))

        surfaceView = SurfaceView(this)
        surfaceView.setOnTouchListener { _, event ->
            gestureHandler.onTouchEvent(event)
        }
        root.addView(surfaceView, FrameLayout.LayoutParams(-1, -1))

        val chromeView = EditorChromeView(this).apply {
            isClickable = false
            isFocusable = false
        }
        root.addView(chromeView, FrameLayout.LayoutParams(-1, -1))

        val pickButton = ImageButton(this).apply {
            setImageResource(android.R.drawable.ic_menu_upload)
            setColorFilter(Color.WHITE)
            setBackgroundColor(Color.argb(220, 124, 92, 255))
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

        axisGizmoView = AxisGizmoView(this)
        val gizmoSize = (118 * resources.displayMetrics.density).toInt()
        val gizmoParams = FrameLayout.LayoutParams(gizmoSize, gizmoSize, Gravity.TOP or Gravity.END).apply {
            topMargin = (14 * resources.displayMetrics.density).toInt()
            rightMargin = (14 * resources.displayMetrics.density).toInt()
        }
        root.addView(axisGizmoView, gizmoParams)

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
        NativeCamera.destroy()
        super.onDestroy()
    }

    override fun doFrame(frameTimeNanos: Long) {
        if (::modelViewer.isInitialized && surfaceView.width > 0 && surfaceView.height > 0) {
            val delta = if (lastFrameTimeNanos == 0L) 1f / 60f
            else ((frameTimeNanos - lastFrameTimeNanos) / 1_000_000_000f).coerceIn(0.001f, 0.08f)
            lastFrameTimeNanos = frameTimeNanos

            val aspect = surfaceView.width.toDouble() / surfaceView.height.toDouble()
            NativeCamera.setScreenSize(surfaceView.width, surfaceView.height)
            NativeCamera.update(delta)
            applyNativeCamera(aspect)
            modelViewer.render(frameTimeNanos)
        }
        Choreographer.getInstance().postFrameCallback(this)
    }

    private fun applyNativeCamera(aspect: Double) {
        val c = NativeCamera.getCameraState()
        modelViewer.camera.setProjection(45.0, aspect, 0.05, 1000.0, com.google.android.filament.Camera.Fov.VERTICAL)
        modelViewer.camera.lookAt(
            c[0].toDouble(), c[1].toDouble(), c[2].toDouble(),
            c[3].toDouble(), c[4].toDouble(), c[5].toDouble(),
            c[6].toDouble(), c[7].toDouble(), c[8].toDouble()
        )
        if (::axisGizmoView.isInitialized) {
            axisGizmoView.yaw = c[9]
            axisGizmoView.pitch = c[10]
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
            NativeCamera.reset(0f, 0f, 0f, 1f)
            lastFrameTimeNanos = 0L

            statusText.text = "Loaded: $name | 1 finger orbit, pinch zoom, 2 fingers pan"
        }.onFailure { error ->
            statusText.text = "Failed to load GLB"
            Toast.makeText(this, error.message ?: "Unknown error", Toast.LENGTH_LONG).show()
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
