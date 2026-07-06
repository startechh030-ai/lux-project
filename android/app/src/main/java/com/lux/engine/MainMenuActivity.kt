package com.lux.engine

import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

/**
 * Main menu activity.
 *
 * Displays:
 *   - A list of available mini-games (queried from native via JNI)
 *   - Nakama authentication status
 *   - Settings button
 */
class MainMenuActivity : AppCompatActivity() {

    private lateinit var gameListRecyclerView: RecyclerView
    private lateinit var authStatusText: TextView
    private lateinit var settingsButton: Button

    private val gameAdapter = GameListAdapter { gameId ->
        launchGame(gameId)
    }

    // ── Native methods ────────────────────────────────────────────────
    companion object {
        init {
            System.loadLibrary("lux_shared")
        }

        /** Returns an array of game ID strings from the native registry. */
        private external fun nativeGetGameList(): Array<String>
    }

    // ── Lifecycle ─────────────────────────────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main_menu)

        // UI references
        gameListRecyclerView = findViewById(R.id.game_list)
        authStatusText = findViewById(R.id.auth_status)
        settingsButton = findViewById(R.id.settings_button)

        // Setup game list
        gameListRecyclerView.layoutManager = LinearLayoutManager(this)
        gameListRecyclerView.adapter = gameAdapter

        // Load games from native
        refreshGameList()

        // Settings
        settingsButton.setOnClickListener {
            // TODO: open settings dialog
            Toast.makeText(this, "Settings — coming soon", Toast.LENGTH_SHORT).show()
        }

        // Auth status (stub)
        authStatusText.text = "Offline mode"
    }

    override fun onResume() {
        super.onResume()
        refreshGameList()
    }

    // ── Game launching ────────────────────────────────────────────────

    private fun launchGame(gameId: String) {
        val intent = Intent(this, GameActivity::class.java).apply {
            putExtra("GAME_ID", gameId)
        }
        startActivity(intent)
    }

    // ── UI helpers ────────────────────────────────────────────────────

    private fun refreshGameList() {
        try {
            val gameIds = nativeGetGameList()
            gameAdapter.submitList(gameIds.toList())
        } catch (e: UnsatisfiedLinkError) {
            // Native library not loaded yet — use fallback list
            gameAdapter.submitList(listOf("racing"))
            Toast.makeText(this, "Native engine not loaded", Toast.LENGTH_SHORT).show()
        }
    }
}
