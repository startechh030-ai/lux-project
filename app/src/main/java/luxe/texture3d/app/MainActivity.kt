package luxe.texture3d.app

import androidx.appcompat.app.AppCompatActivity
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.view.Choreographer
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.SurfaceView
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import com.google.android.filament.Skybox
import com.google.android.filament.utils.Float3
import com.google.android.filament.utils.KTX1Loader
import com.google.android.filament.utils.ModelViewer
import com.google.android.filament.utils.Utils
import java.nio.ByteBuffer
import java.nio.ByteOrder

class MainActivity : AppCompatActivity(), Choreographer.FrameCallback {
    private lateinit var surface: SurfaceView
    private lateinit var viewer: ModelViewer
    private lateinit var status: TextView
    private val nativeCamera = NativeCamera()
    private var solidSkybox: Skybox? = null
    private var rendering = false

    private val openModel = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) loadGlb(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Utils.init()
        window.statusBarColor = 0xff0f172a.toInt()
        window.navigationBarColor = 0xff0f172a.toInt()
        // This legacy fullscreen flag is reliable across our API 26+ device range.
        window.setFlags(
            WindowManager.LayoutParams.FLAG_FULLSCREEN,
            WindowManager.LayoutParams.FLAG_FULLSCREEN
        )

        val root = FrameLayout(this).apply { setBackgroundColor(0xff0f172a.toInt()) }
        surface = SurfaceView(this).apply { setZOrderOnTop(false) }
        root.addView(surface, FrameLayout.LayoutParams(-1, -1))

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

        viewer = ModelViewer(surface, manipulator = null)
        loadEnvironment()
        installTouchController()
        surface.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            nativeCamera.nativeSetViewport(surface.width.coerceAtLeast(1), surface.height.coerceAtLeast(1))
        }
        nativeCamera.nativeReset()
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
            val bytes = contentResolver.openInputStream(uri)?.use { it.readBytes() }
                ?: throw IllegalStateException("The selected file could not be opened")
            if (bytes.size > 300 * 1024 * 1024) throw IllegalArgumentException("GLB is larger than the 300 MB safety limit")
            val buffer = ByteBuffer.allocateDirect(bytes.size).order(ByteOrder.nativeOrder()).apply { put(bytes); flip() }
            viewer.loadModelGlb(buffer)
            if (viewer.asset == null) throw IllegalArgumentException("Filament could not parse this GLB")
            // Camera math orbits the world origin, so normalize the model to
            // that same pivot instead of ModelViewer's default z = -4 center.
            viewer.transformToUnitCube(Float3(0f, 0f, 0f))
            nativeCamera.nativeReset()
            status.text = "$name  •  ORBIT VIEW"
        } catch (e: Exception) {
            status.text = "NO MODEL LOADED"
            Toast.makeText(this, e.message ?: "Could not load GLB", Toast.LENGTH_LONG).show()
        }
    }

    private fun displayName(uri: Uri): String {
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) return it.getString(0)
        }
        return uri.lastPathSegment ?: "model.glb"
    }

    private fun installTouchController() {
        val scale = ScaleGestureDetector(this, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                nativeCamera.nativeZoom(detector.scaleFactor); return true
            }
        })
        val taps = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent) = true
            override fun onDoubleTap(e: MotionEvent): Boolean {
                nativeCamera.nativeReset(); status.text = "CAMERA RESET"; return true
            }
        })
        var lastX = 0f; var lastY = 0f; var lastCount = 0
        surface.isClickable = true
        surface.isFocusable = true
        surface.setOnTouchListener { view, event ->
            if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                view.parent?.requestDisallowInterceptTouchEvent(true)
            }
            scale.onTouchEvent(event); taps.onTouchEvent(event)
            val count = event.pointerCount
            val cx = (0 until count).sumOf { event.getX(it).toDouble() }.toFloat() / count
            val cy = (0 until count).sumOf { event.getY(it).toDouble() }.toFloat() / count
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> { lastX = cx; lastY = cy; lastCount = count }
                MotionEvent.ACTION_MOVE -> {
                    if (!scale.isInProgress && count == lastCount) {
                        if (count == 1) nativeCamera.nativeOrbit(cx-lastX, cy-lastY)
                        else if (count >= 2) nativeCamera.nativePan(cx-lastX, cy-lastY)
                    }
                    lastX = cx; lastY = cy; lastCount = count
                }
                MotionEvent.ACTION_POINTER_UP -> { lastCount = -1 }
            }
            true
        }
    }

    override fun onResume() { super.onResume(); rendering = true; Choreographer.getInstance().postFrameCallback(this) }
    override fun onPause() { rendering = false; Choreographer.getInstance().removeFrameCallback(this); super.onPause() }
    override fun doFrame(frameTimeNanos: Long) {
        if (!rendering) return
        val p = nativeCamera.nativeUpdate(frameTimeNanos / 1_000_000_000.0)
        viewer.camera.lookAt(p[0].toDouble(), p[1].toDouble(), p[2].toDouble(), p[3].toDouble(), p[4].toDouble(), p[5].toDouble(), 0.0, 1.0, 0.0)
        viewer.render(frameTimeNanos)
        Choreographer.getInstance().postFrameCallback(this)
    }
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
}
