package com.lux.engine

import android.content.Context
import android.graphics.PixelFormat
import android.opengl.GLSurfaceView
import android.os.Bundle
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.appcompat.app.AppCompatActivity
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

/**
 * Game activity — hosts a SurfaceView and bridges input to the native engine.
 *
 * The native C++ engine (lux_shared.so) handles all rendering via Filament.
 * This activity just provides the Surface and forwards touch events via JNI.
 */
class GameActivity : AppCompatActivity() {

    private lateinit var gameSurfaceView: SurfaceView
    private lateinit var hudOverlay: HUDOverlay

    private var nativeEnginePtr: Long = 0
    private var gameId: String = ""

    // ── Native methods ────────────────────────────────────────────────
    companion object {
        init {
            System.loadLibrary("lux_shared")
        }

        private external fun nativeCreateEngine(
            assetManager: android.content.res.AssetManager,
            width: Int, height: Int
        ): Long

        private external fun nativeDestroyEngine(enginePtr: Long)

        private external fun nativeOnSurfaceCreated(
            enginePtr: Long, surface: android.view.Surface
        )

        private external fun nativeOnSurfaceChanged(
            enginePtr: Long, width: Int, height: Int
        )

        private external fun nativeOnSurfaceDestroyed(enginePtr: Long)

        private external fun nativeTick(enginePtr: Long, dt: Float)

        private external fun nativeStartGame(enginePtr: Long, gameId: String): Boolean

        private external fun nativeStopGame(enginePtr: Long)

        private external fun nativeOnTouch(
            enginePtr: Long, pointerId: Int, x: Float, y: Float, pressed: Boolean
        )
    }

    // ── Lifecycle ─────────────────────────────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_game)

        gameId = intent.getStringExtra("GAME_ID") ?: "racing"

        // SurfaceView for the native engine's rendering
        gameSurfaceView = findViewById(R.id.game_surface)
        gameSurfaceView.holder.addCallback(surfaceCallback)

        // HUD overlay (Compose-based)
        hudOverlay = HUDOverlay(this)
        // In production, set up Compose or add overlay View

        // Touch handling
        gameSurfaceView.setOnTouchListener { _, event -> handleTouch(event) }
    }

    override fun onResume() {
        super.onResume()

        if (nativeEnginePtr == 0L) {
            nativeEnginePtr = nativeCreateEngine(
                assets,
                gameSurfaceView.width.coerceAtLeast(720),
                gameSurfaceView.height.coerceAtLeast(1280)
            )
        }

        // Start the selected mini-game
        if (nativeEnginePtr != 0L) {
            nativeStartGame(nativeEnginePtr, gameId)
        }
    }

    override fun onPause() {
        super.onPause()
        if (nativeEnginePtr != 0L) {
            nativeStopGame(nativeEnginePtr)
        }
    }

    override fun onDestroy() {
        if (nativeEnginePtr != 0L) {
            nativeOnSurfaceDestroyed(nativeEnginePtr)
            nativeDestroyEngine(nativeEnginePtr)
            nativeEnginePtr = 0L
        }
        super.onDestroy()
    }

    // ── Surface Callback ──────────────────────────────────────────────

    private val surfaceCallback = object : SurfaceHolder.Callback {
        override fun surfaceCreated(holder: SurfaceHolder) {
            if (nativeEnginePtr != 0L) {
                nativeOnSurfaceCreated(nativeEnginePtr, holder.surface)
            }
        }

        override fun surfaceChanged(
            holder: SurfaceHolder, format: Int, width: Int, height: Int
        ) {
            if (nativeEnginePtr != 0L) {
                nativeOnSurfaceChanged(nativeEnginePtr, width, height)
            }
        }

        override fun surfaceDestroyed(holder: SurfaceHolder) {
            if (nativeEnginePtr != 0L) {
                nativeOnSurfaceDestroyed(nativeEnginePtr)
            }
        }
    }

    // ── Input forwarding ──────────────────────────────────────────────

    private fun handleTouch(event: MotionEvent): Boolean {
        val ptr = nativeEnginePtr
        if (ptr == 0L) return false

        val action = event.actionMasked
        for (i in 0 until event.pointerCount) {
            val id = event.getPointerId(i)
            val x = event.getX(i)
            val y = event.getY(i)

            when (action) {
                MotionEvent.ACTION_DOWN,
                MotionEvent.ACTION_POINTER_DOWN -> {
                    nativeOnTouch(ptr, id, x, y, true)
                }
                MotionEvent.ACTION_UP,
                MotionEvent.ACTION_POINTER_UP -> {
                    nativeOnTouch(ptr, id, x, y, false)
                }
                MotionEvent.ACTION_MOVE -> {
                    nativeOnTouch(ptr, id, x, y, true)
                }
            }
        }
        return true
    }

    // ── Frame callback ────────────────────────────────────────────────

    /**
     * Called from a Choreographer or GL thread to drive the game loop.
     * In production, this would be called at vsync rate.
     */
    fun onFrameTick(dt: Float) {
        if (nativeEnginePtr != 0L) {
            nativeTick(nativeEnginePtr, dt)
        }
    }
}
