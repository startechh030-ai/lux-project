package luxe.texture3d.app

import androidx.appcompat.app.AppCompatActivity
import android.app.ActivityManager
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.opengl.Matrix
import android.provider.OpenableColumns
import android.view.Choreographer
import android.view.Gravity
import android.view.SurfaceView
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import com.google.android.filament.Skybox
import com.google.android.filament.utils.KTX1Loader
import com.google.android.filament.utils.Utils
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs

class MainActivity : AppCompatActivity(), Choreographer.FrameCallback {
    private lateinit var surface: SurfaceView
    private lateinit var cameraInput: CameraInputView
    private lateinit var viewer: LuxeModelViewer
    private val nativeCamera = NativeCamera()
    private val cameraPose = FloatArray(9)
    private val mainHandler = Handler(Looper.getMainLooper())
    private lateinit var status: TextView
    private var solidSkybox: Skybox? = null
    private var rendering = false

    private val openModel = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) loadGlb(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Utils.init()
        window.statusBarColor = android.graphics.Color.TRANSPARENT
        window.navigationBarColor = android.graphics.Color.TRANSPARENT
        // This legacy fullscreen flag is reliable across our API 26+ device range.
        window.setFlags(
            WindowManager.LayoutParams.FLAG_FULLSCREEN,
            WindowManager.LayoutParams.FLAG_FULLSCREEN
        )

        val root = FrameLayout(this).apply { setBackgroundColor(0xff0f172a.toInt()) }
        surface = SurfaceView(this).apply { setZOrderOnTop(false) }
        root.addView(surface, FrameLayout.LayoutParams(-1, -1))

        // A normal transparent View captures input above the hardware-backed
        // SurfaceView. This avoids device-specific SurfaceView touch failures.
        cameraInput = CameraInputView(this).apply {
            camera = nativeCamera
            onGesture = { gesture -> status.text = "CAMERA INPUT  •  $gesture" }
            onOrbitTouch = { x, y -> pickPivot(x, y) }
        }
        root.addView(cameraInput, FrameLayout.LayoutParams(-1, -1))

        val open = ImageButton(this).apply {
            setImageResource(luxe.texture3d.app.R.drawable.ic_open)
            contentDescription = "Open GLB model"
            setBackgroundResource(luxe.texture3d.app.R.drawable.panel_bg)
            setColorFilter(0xffbae6fd.toInt())
            setPadding(dp(12), dp(12), dp(12), dp(12))
            setOnClickListener { openModel.launch(arrayOf("model/gltf-binary", "application/octet-stream", "*/*")) }
        }
        root.addView(open, FrameLayout.LayoutParams(dp(52), dp(52), Gravity.TOP or Gravity.START).apply {
            leftMargin = dp(16); topMargin = dp(16)
        })

        status = TextView(this).apply {
            text = "OPEN A GLB  •  Drag: orbit  •  Pinch: zoom  •  Two fingers: pan"
            setTextColor(0xffbae6fd.toInt()); textSize = 12f; gravity = Gravity.CENTER
            setBackgroundResource(luxe.texture3d.app.R.drawable.panel_bg)
            setPadding(dp(16), 0, dp(16), 0)
        }
        root.addView(status, FrameLayout.LayoutParams(-2, dp(38), Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply {
            bottomMargin = dp(12)
        })
        setContentView(root)
        applyImmersiveMode()

        // Forked viewer keeps Filament loading/rendering but never overwrites
        // the camera pose supplied by the Luxe C++ controller.
        viewer = LuxeModelViewer(surface)
        nativeCamera.nativeSetViewport(surface.width.coerceAtLeast(1), surface.height.coerceAtLeast(1))
        nativeCamera.nativeReset()
        loadEnvironment()
    }

    private fun loadEnvironment() {
        val ibl = readAsset("environments/shanghai_bund_2k_ibl.ktx")
        KTX1Loader.createIndirectLight(viewer.engine, ibl).also { bundle ->
            viewer.scene.indirectLight = bundle.indirectLight
            viewer.indirectLightCubemap = bundle.cubemap
            bundle.indirectLight?.intensity = 30_000f
        }
        // Keep the HDR only as invisible image-based lighting. The visible
        // background is a neutral Blender-like gray and does not light models.
        solidSkybox = Skybox.Builder()
            .color(0.055f, 0.065f, 0.075f, 1.0f)
            .build(viewer.engine)
        viewer.scene.skybox = solidSkybox
    }

    private fun readAsset(path: String): ByteBuffer {
        val bytes = assets.open(path).use { it.readBytes() }
        return ByteBuffer.allocateDirect(bytes.size).order(ByteOrder.nativeOrder()).apply { put(bytes); flip() }
    }

    private fun loadGlb(uri: Uri) {
        try {
            val name = displayName(uri)
            if (!name.lowercase().endsWith(".glb")) throw IllegalArgumentException("Please choose a .glb file")
            status.text = "LOADING  •  $name"
            // Empty-scene gestures must never alter the next model's pivot.
            cameraInput.inputEnabled = false
            cameraInput.clearPivotFeedback()
            nativeCamera.nativeReset()
            val fileSize = selectedFileSize(uri)
            val safeLimit = safeGlbImportLimit()
            if (fileSize <= 0L) {
                throw IllegalArgumentException("Android did not provide this GLB's size")
            }
            if (fileSize > safeLimit) {
                throw IllegalArgumentException(
                    "This ${formatMb(fileSize)} MB GLB exceeds this device's ${formatMb(safeLimit)} MB safe import limit"
                )
            }

            // Stream directly into one native buffer. The previous readBytes()
            // path held a heap byte array and a direct copy at the same time.
            val buffer = readDirectBuffer(uri, fileSize)
            viewer.loadModelGlb(buffer)
            if (viewer.asset == null) throw IllegalArgumentException("Filament could not parse this GLB")
            // Native camera and normalized model share the world origin.
            viewer.transformToUnitCube(com.google.android.filament.utils.Float3(0f, 0f, 0f))
            nativeCamera.nativeReset()
            cameraInput.inputEnabled = true
            status.text = "$name  •  ORBIT VIEW"
        } catch (_: OutOfMemoryError) {
            viewer.destroyModel()
            cameraInput.inputEnabled = false
            status.text = "MODEL TOO HEAVY"
            Toast.makeText(
                this,
                "Not enough memory for this model. Try a lower-poly GLB or smaller textures.",
                Toast.LENGTH_LONG
            ).show()
        } catch (e: Exception) {
            cameraInput.inputEnabled = viewer.asset != null
            status.text = if (viewer.asset != null) "PREVIOUS MODEL ACTIVE" else "NO MODEL LOADED"
            Toast.makeText(this, e.message ?: "Could not load GLB", Toast.LENGTH_LONG).show()
        }
    }

    private fun pickPivot(screenX: Float, screenY: Float) {
        if (viewer.asset == null || surface.width <= 0 || surface.height <= 0) return
        val pickY = surface.height - screenY.toInt()
        viewer.view.pick(screenX.toInt(), pickY, mainHandler) { result ->
            if (result.renderable == 0) {
                nativeCamera.nativeQueuePivot(0f, 0f, 0f)
                cameraInput.clearPivotFeedback()
                return@pick
            }
            val world = unproject(result.fragCoords[0], result.fragCoords[1], result.depth)
            if (world != null && isValidNormalizedPivot(world)) {
                nativeCamera.nativeQueuePivot(world[0], world[1], world[2])
                cameraInput.showPivotFeedback(
                    result.fragCoords[0], surface.height-result.fragCoords[1]
                )
                status.text = "MESH PIVOT  •  READY"
            } else {
                // A malformed depth result must never pull the camera away
                // from the normalized model. Fall back to its true center.
                nativeCamera.nativeQueuePivot(0f, 0f, 0f)
                cameraInput.clearPivotFeedback()
                status.text = "MODEL PIVOT  •  READY"
            }
        }
    }

    /** Converts Filament GL fragment coordinates and depth into world space. */
    private fun unproject(x: Float, y: Float, depth: Float): FloatArray? {
        val viewport = viewer.view.viewport
        if (viewport.width <= 0 || viewport.height <= 0) return null
        // Pass explicitly typed arrays because Camera exposes both FloatArray
        // and DoubleArray overloads for getViewMatrix().
        val projectionD = viewer.camera.getProjectionMatrix(DoubleArray(16))
        val viewD = viewer.camera.getViewMatrix(DoubleArray(16))
        val projection = FloatArray(16) { index -> projectionD[index].toFloat() }
        val viewMatrix = FloatArray(16) { index -> viewD[index].toFloat() }
        val viewProjection = FloatArray(16)
        val inverse = FloatArray(16)
        Matrix.multiplyMM(viewProjection, 0, projection, 0, viewMatrix, 0)
        if (!Matrix.invertM(inverse, 0, viewProjection, 0)) return null

        val ndc = floatArrayOf(
            ((x - viewport.left) / viewport.width) * 2f - 1f,
            ((y - viewport.bottom) / viewport.height) * 2f - 1f,
            // Filament's projection matrix consumes its native 0..1 depth.
            // Remapping this to -1..1 produces a distant, invalid pivot.
            depth,
            1f
        )
        val world = FloatArray(4)
        Matrix.multiplyMV(world, 0, inverse, 0, ndc, 0)
        if (abs(world[3]) < 1e-6f) return null
        return floatArrayOf(world[0] / world[3], world[1] / world[3], world[2] / world[3])
    }

    private fun isValidNormalizedPivot(point: FloatArray): Boolean {
        if (point.size < 3 || point.any { !it.isFinite() }) return false
        // transformToUnitCube() places all original bounds inside [-1, 1].
        // A small margin tolerates skinning and numeric reconstruction error.
        if (abs(point[0]) > 1.25f || abs(point[1]) > 1.25f || abs(point[2]) > 1.25f) return false
        val radiusSquared = point[0] * point[0] + point[1] * point[1] + point[2] * point[2]
        return radiusSquared <= 1.9f * 1.9f
    }

    private fun selectedFileSize(uri: Uri): Long {
        contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use {
            if (it.moveToFirst() && !it.isNull(0)) return it.getLong(0)
        }
        return contentResolver.openFileDescriptor(uri, "r")?.use { it.statSize } ?: -1L
    }

    private fun safeGlbImportLimit(): Long {
        val memoryClassMb = (getSystemService(ACTIVITY_SERVICE) as ActivityManager).memoryClass.toLong()
        // GLB resources expand after parsing. Keep source data below 20% of the
        // app heap, with conservative bounds for weak and high-memory devices.
        val limitMb = (memoryClassMb / 5L).coerceIn(32L, 128L)
        return limitMb * 1024L * 1024L
    }

    private fun readDirectBuffer(uri: Uri, size: Long): ByteBuffer {
        if (size > Int.MAX_VALUE) throw IllegalArgumentException("GLB is too large for Android")
        val output = ByteBuffer.allocateDirect(size.toInt()).order(ByteOrder.nativeOrder())
        val descriptor = contentResolver.openFileDescriptor(uri, "r")
            ?: throw IllegalStateException("The selected file could not be opened")
        descriptor.use { pfd ->
            FileInputStream(pfd.fileDescriptor).channel.use { channel ->
                while (output.hasRemaining()) {
                    if (channel.read(output) < 0) break
                }
            }
        }
        if (output.position() != size.toInt()) {
            throw IllegalStateException("The GLB could not be read completely")
        }
        output.flip()
        return output
    }

    private fun formatMb(bytes: Long) = bytes / (1024L * 1024L)

    private fun displayName(uri: Uri): String {
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) return it.getString(0)
        }
        return uri.lastPathSegment ?: "model.glb"
    }


    @Suppress("DEPRECATION")
    private fun applyImmersiveMode() {
        window.decorView.systemUiVisibility =
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
            View.SYSTEM_UI_FLAG_FULLSCREEN or
            View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
            View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
            View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
        if (android.os.Build.VERSION.SDK_INT >= 28) {
            window.attributes = window.attributes.apply {
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) applyImmersiveMode()
    }

    override fun onResume() { super.onResume(); applyImmersiveMode(); rendering = true; Choreographer.getInstance().postFrameCallback(this) }
    override fun onPause() { rendering = false; Choreographer.getInstance().removeFrameCallback(this); super.onPause() }
    override fun doFrame(frameTimeNanos: Long) {
        if (!rendering) return
        // Reuse one pose array for the app lifetime; no JNI array allocation
        // or young-generation GC pressure occurs in the render loop.
        nativeCamera.nativeUpdate(frameTimeNanos / 1_000_000_000.0, cameraPose)
        viewer.camera.lookAt(
            cameraPose[0].toDouble(), cameraPose[1].toDouble(), cameraPose[2].toDouble(),
            cameraPose[3].toDouble(), cameraPose[4].toDouble(), cameraPose[5].toDouble(),
            cameraPose[6].toDouble(), cameraPose[7].toDouble(), cameraPose[8].toDouble()
        )
        viewer.render(frameTimeNanos)
        Choreographer.getInstance().postFrameCallback(this)
    }
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
}
