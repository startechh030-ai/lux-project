package com.arena.texturepaint

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.view.Gravity
import android.view.View
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.ComponentActivity
import java.io.File

class MainActivity : ComponentActivity() {
    private var nativeHandle: Long = 0L
    private lateinit var root: FrameLayout
    private lateinit var filamentViewport: FilamentViewport
    private lateinit var uvPaintView: UvPaintView
    private lateinit var statusText: TextView
    private var showingUv = false
    private var currentModel: File? = null

    private val pickModel = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            result.data?.data?.let { importModel(it) }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        nativeHandle = NativeTextureCore.createProject()
        buildUi()
    }

    override fun onDestroy() {
        if (nativeHandle != 0L) NativeTextureCore.destroyProject(nativeHandle)
        nativeHandle = 0L
        super.onDestroy()
    }

    private fun buildUi() {
        root = FrameLayout(this)
        filamentViewport = FilamentViewport(this)
        uvPaintView = UvPaintView(this).apply { visibility = View.GONE }
        root.addView(filamentViewport, FrameLayout.LayoutParams(-1, -1))
        root.addView(uvPaintView, FrameLayout.LayoutParams(-1, -1))

        val toolbar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(12, 10, 12, 10)
            setBackgroundColor(Color.argb(225, 12, 14, 18))
        }
        fun button(label: String, action: () -> Unit) = Button(this).apply {
            text = label
            setOnClickListener { action() }
        }
        toolbar.addView(button("Import GLB") { openPicker() })
        toolbar.addView(button("Unwrap") { unwrap() })
        toolbar.addView(button("3D/UV") { toggleView() })
        toolbar.addView(button("Erase") { uvPaintView.eraser = !uvPaintView.eraser; toast("Eraser: ${uvPaintView.eraser}") })
        toolbar.addView(button("Save PNG") { saveTexture() })
        root.addView(toolbar, FrameLayout.LayoutParams(-1, -2, Gravity.TOP))

        statusText = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 13f
            setPadding(14, 8, 14, 8)
            setBackgroundColor(Color.argb(210, 0, 0, 0))
            text = "Ready. Import GLB to start."
        }
        root.addView(statusText, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM))
        setContentView(root)
    }

    private fun openPicker() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("model/gltf-binary", "model/gltf+json", "application/octet-stream"))
        }
        pickModel.launch(intent)
    }

    private fun importModel(uri: Uri) {
        val name = displayName(uri) ?: "import.glb"
        val file = FileUtils.copyUriToCache(this, uri, name)
        currentModel = file

        runCatching { filamentViewport.loadGlb(file) }
            .onFailure { status("Filament load failed: ${it.message}") }

        val loaded = NativeTextureCore.loadGltf(nativeHandle, file.absolutePath)
        val stats = NativeTextureCore.getMeshStats(nativeHandle)
        status("Native loaded=$loaded | verts=${stats.getOrNull(0)} idx=${stats.getOrNull(1)} meshes=${stats.getOrNull(2)} prim=${stats.getOrNull(3)} uv=${stats.getOrNull(4) == 1}")
    }

    private fun unwrap() {
        val ok = NativeTextureCore.unwrapAuto(nativeHandle, 2048)
        val stats = NativeTextureCore.getMeshStats(nativeHandle)
        status("xatlas unwrap=$ok | verts=${stats.getOrNull(0)} idx=${stats.getOrNull(1)} uv=${stats.getOrNull(4) == 1}")
    }

    private fun toggleView() {
        showingUv = !showingUv
        uvPaintView.visibility = if (showingUv) View.VISIBLE else View.GONE
        filamentViewport.visibility = if (showingUv) View.GONE else View.VISIBLE
    }

    private fun saveTexture() {
        val out = File(cacheDir, "paint_texture.png")
        val ok = uvPaintView.savePng(out)
        status("Saved paint texture=$ok | ${out.absolutePath}")
    }

    private fun displayName(uri: Uri): String? = runCatching {
        contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (cursor.moveToFirst() && index >= 0) cursor.getString(index) else null
        }
    }.getOrNull()

    private fun status(message: String) { statusText.text = message }
    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
}
