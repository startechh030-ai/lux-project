package com.arena.texturepaint

import android.content.Context
import android.view.Choreographer
import android.view.SurfaceView
import android.widget.FrameLayout
import com.google.android.filament.Engine
import com.google.android.filament.Skybox
import com.google.android.filament.utils.ModelViewer
import java.io.File
import java.nio.ByteBuffer

class FilamentViewport(context: Context) : FrameLayout(context), Choreographer.FrameCallback {
    private val surfaceView = SurfaceView(context)
    private val engine: Engine = Engine.create()
    private val modelViewer: ModelViewer = ModelViewer(surfaceView, engine)
    private var attached = false

    init {
        addView(surfaceView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        modelViewer.scene.skybox = Skybox.Builder()
            .color(0.055f, 0.062f, 0.078f, 1.0f)
            .build(engine)
    }

    fun loadGlb(file: File) {
        val bytes = file.readBytes()
        val buffer = ByteBuffer.allocateDirect(bytes.size)
        buffer.put(bytes)
        buffer.rewind()
        modelViewer.destroyModel()
        modelViewer.loadModelGlb(buffer)
        modelViewer.transformToUnitCube()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (!attached) {
            attached = true
            Choreographer.getInstance().postFrameCallback(this)
        }
    }

    override fun onDetachedFromWindow() {
        attached = false
        Choreographer.getInstance().removeFrameCallback(this)
        super.onDetachedFromWindow()
    }

    override fun doFrame(frameTimeNanos: Long) {
        if (attached) {
            modelViewer.render(frameTimeNanos)
            Choreographer.getInstance().postFrameCallback(this)
        }
    }
}
