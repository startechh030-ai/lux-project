package com.arena.simpleglbviewer

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.view.Gravity
import android.view.SurfaceView
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import com.google.android.filament.Engine
import com.google.android.filament.Skybox
import com.google.android.filament.utils.ModelViewer
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer

class MainActivity : ComponentActivity() {
    private lateinit var root: FrameLayout
    private lateinit var surfaceView: SurfaceView
    private lateinit var modelViewer: ModelViewer
    private lateinit var statusText: TextView

    private val pickGlb = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            result.data?.data?.let { uri -> loadGlbFromUri(uri) }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildUi()
        setupFilament()
    }

    private fun buildUi() {
        root = FrameLayout(this)
        root.setBackgroundColor(Color.rgb(16, 18, 22))

        surfaceView = SurfaceView(this)
        root.addView(surfaceView, FrameLayout.LayoutParams(-1, -1))

        val pickButton = ImageButton(this).apply {
            setImageResource(android.R.drawable.ic_menu_upload)
            setColorFilter(Color.WHITE)
            setBackgroundColor(Color.argb(220, 124, 92, 255))
            contentDescription = "Pick GLB file"
            setPadding(22, 22, 22, 22)
            setOnClickListener { openFilePicker() }
        }
        val buttonSize = (64 * resources.displayMetrics.density).toInt()
        val buttonParams = FrameLayout.LayoutParams(buttonSize, buttonSize, Gravity.TOP or Gravity.END).apply {
            topMargin = (24 * resources.displayMetrics.density).toInt()
            rightMargin = (18 * resources.displayMetrics.density).toInt()
        }
        root.addView(pickButton, buttonParams)

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
        val engine = Engine.create()
        modelViewer = ModelViewer(surfaceView, engine)
        modelViewer.scene.skybox = Skybox.Builder()
            .color(0.055f, 0.062f, 0.078f, 1.0f)
            .build(engine)
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

            statusText.text = "Loaded: $name"
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
