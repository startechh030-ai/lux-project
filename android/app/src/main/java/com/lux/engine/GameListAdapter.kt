package com.lux.engine

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

/**
 * Simple adapter for displaying the list of available mini-games.
 */
class GameListAdapter(
    private val onGameClick: (String) -> Unit
) : RecyclerView.Adapter<GameListAdapter.ViewHolder>() {

    private var gameIds: List<String> = emptyList()

    fun submitList(list: List<String>) {
        gameIds = list
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_game, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val gameId = gameIds[position]
        holder.titleText.text = gameId.replaceFirstChar { it.uppercase() }
        holder.itemView.setOnClickListener {
            onGameClick(gameId)
        }
    }

    override fun getItemCount(): Int = gameIds.size

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val titleText: TextView = view.findViewById(R.id.game_title)
    }
}
