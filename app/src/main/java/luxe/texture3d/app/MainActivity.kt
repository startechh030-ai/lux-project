package luxe.texture3d.app

import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AlertDialog
import android.app.ActivityManager
import android.net.Uri
import android.os.Bundle
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
import com.google.android.filament.utils.Manipulator
import com.google.android.filament.utils.ModelViewer
import com.google.android.filament.utils.Utils
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

class MainActivity : AppCompatActivity(), Choreographer.FrameCallback {
    private lateinit var surface: SurfaceView
    private lateinit var cameraInput: CameraInputView
    private lateinit var viewer: ModelViewer
    private lateinit var manipulator: Manipulator
    private lateinit var editorGrid: EditorGrid
    private lateinit var status: TextView
    private var solidSkybox: Skybox? = null
    private var rendering = false

    private val openModel = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) handleSelectedModel(uri)
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
            onGesture = { gesture -> status.text = "CAMERA INPUT  •  $gesture" }
            onDoubleTap = { resetCameraHome() }
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
            leftMargin = dp(10); topMargin = dp(10)
        })

        val settings = ImageButton(this).apply {
            setImageResource(luxe.texture3d.app.R.drawable.ic_settings)
            contentDescription = "Settings"
            setBackgroundResource(luxe.texture3d.app.R.drawable.panel_bg)
            setPadding(dp(13), dp(13), dp(13), dp(13))
            setOnClickListener {
                Toast.makeText(this@MainActivity, "Settings panel comes next", Toast.LENGTH_SHORT).show()
            }
        }
        root.addView(settings, FrameLayout.LayoutParams(dp(50), dp(50), Gravity.BOTTOM or Gravity.START).apply {
            leftMargin = dp(11); bottomMargin = dp(12)
        })


        status = TextView(this).apply {
            text = "OPEN A GLB  •  Drag: orbit  •  Pinch: zoom  •  Two fingers: pan"
            setTextColor(0xffbae6fd.toInt()); textSize = 12f; gravity = Gravity.CENTER
            setBackgroundResource(luxe.texture3d.app.R.drawable.panel_bg)
            setPadding(dp(16), 0, dp(16), 0)
        }
        status.alpha = 0f
        root.addView(status, FrameLayout.LayoutParams(1, 1, Gravity.BOTTOM))
        setContentView(root)
        applyImmersiveMode()

        // One Filament-native camera owner, with our low-latency touch adapter.
        manipulator = Manipulator.Builder()
            .targetPosition(0f, 0.7f, -4f)
            .orbitHomePosition(4f, 3.4f, 4f)
            // Filament defaults to 0.01. A lower value gives the deliberate,
            // weighted response expected from a mobile sculpting viewport.
            .orbitSpeed(0.0035f, 0.0035f)
            .zoomSpeed(0.012f)
            // Pan against a plane through the model target, not Filament's
            // default z=0 plane. This makes pan usable across the viewport.
            .groundPlane(0f, 0f, 1f, 4f)
            .viewport(surface.width.coerceAtLeast(1), surface.height.coerceAtLeast(1))
            .build(Manipulator.Mode.ORBIT)
        cameraInput.manipulator = manipulator
        viewer = ModelViewer(surface, manipulator = manipulator)
        loadEnvironment()
        editorGrid = EditorGrid(
            viewer.engine, viewer.scene,
            readAsset("materials/luxe_grid.filamat"),
            readAsset("materials/luxe_lines.filamat")
        )
        cameraInput.inputEnabled = true
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

    private fun resetCameraHome() {
        if (::manipulator.isInitialized) {
            manipulator.jumpToBookmark(manipulator.homeBookmark)
        }
    }

    private fun handleSelectedModel(uri: Uri) {
        if (viewer.asset == null) {
            loadGlb(uri)
            return
        }
        AlertDialog.Builder(this)
            .setTitle("Replace current model?")
            .setMessage("The current viewer supports one GLB project at a time.")
            .setPositiveButton("Replace") { _, _ -> loadGlb(uri) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun loadGlb(uri: Uri) {
        try {
            val name = displayName(uri)
            if (!name.lowercase().endsWith(".glb")) throw IllegalArgumentException("Please choose a .glb file")
            status.text = "LOADING  •  $name"
            // Empty-scene gestures must never alter the next model's pivot.
            cameraInput.inputEnabled = false
            manipulator.grabEnd()
            manipulator.jumpToBookmark(manipulator.homeBookmark)
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
            // Normalize the asset, center it horizontally, and place its
            // lowest bound directly on the y=0 editor ground.
            ModelPlacement.placeOnGround(viewer.engine, viewer.asset!!)
            manipulator.jumpToBookmark(manipulator.homeBookmark)
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
        // ModelViewer.render() reads its native Manipulator and applies the
        // sole camera pose immediately before rendering.
        viewer.render(frameTimeNanos)
        Choreographer.getInstance().postFrameCallback(this)
    }
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
}
