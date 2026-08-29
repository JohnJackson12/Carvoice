package com.carvoice.app

import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView

class SongAdapter(
    private var songs: List<Song>,
    private val onClick: (Int) -> Unit,
    private val onLongClick: (Int) -> Unit,
) : RecyclerView.Adapter<SongAdapter.ViewHolder>() {

    /** Index (into `songs`, the currently-displayed/filtered list) of the
     * song that's actually playing right now - highlighted with a
     * distinct background so it's obvious at a glance which track is
     * current, same idea as the "now playing" row tag on the Windows
     * app's song list. -1 means nothing playing is in the current
     * (possibly filtered) view. */
    var playingIndex: Int = -1
        set(value) {
            val old = field
            field = value
            if (old in songs.indices) notifyItemChanged(old)
            if (value in songs.indices) notifyItemChanged(value)
        }

    class ViewHolder(view: android.view.View) : RecyclerView.ViewHolder(view) {
        val number: android.widget.TextView = view.findViewById(R.id.songNumber)
        val title: android.widget.TextView = view.findViewById(R.id.songTitle)
        val artist: android.widget.TextView = view.findViewById(R.id.songArtist)
        val rating: android.widget.TextView = view.findViewById(R.id.songRating)
    }

    fun updateSongs(newSongs: List<Song>, newPlayingIndex: Int) {
        songs = newSongs
        playingIndex = newPlayingIndex
        notifyDataSetChanged()
    }

    /** Position of [uri] in the currently-displayed (filtered) list, or -1
     * if it isn't showing right now - lets MainActivity know whether the
     * playing row is even in view without duplicating this list here. */
    fun positionOf(uri: android.net.Uri?): Int {
        if (uri == null) return -1
        return songs.indexOfFirst { it.uri == uri }
    }

    /** Re-reads just this song's saved rating from disk and redraws its
     * row, if it's currently showing - called right after the star row in
     * the now-playing panel changes a rating, so the list reflects it
     * immediately instead of waiting for the next full refresh. */
    fun refreshRating(uri: android.net.Uri?) {
        val index = positionOf(uri)
        if (index >= 0) notifyItemChanged(index)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_song, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val song = songs[position]
        val context = holder.itemView.context

        holder.number.text = if (song.folderIndex > 0) song.folderIndex.toString() else ""
        holder.title.text = song.title
        // Folder-scanned songs (the common case for this app, since
        // MediaStore's whole-device library is off by default) have no
        // artist metadata at all - showing an empty second line on every
        // single row was the actual cause of the "extra line between
        // songs" look. Collapse it away entirely when there's nothing to
        // show instead of reserving blank space for it.
        if (song.artist.isBlank()) {
            holder.artist.visibility = View.INVISIBLE
        } else {
            holder.artist.visibility = View.VISIBLE
            holder.artist.text = song.artist
        }

        val rating = SongMetadataStore.rating(context, song.uri.toString())
        holder.rating.text = if (rating > 0) "\u2605".repeat(rating) else "\u2606"

        if (position == playingIndex) {
            holder.itemView.setBackgroundColor(context.getColor(R.color.now_playing_row))
            holder.title.setTextColor(context.getColor(R.color.now_playing_text))
        } else {
            holder.itemView.setBackgroundColor(Color.TRANSPARENT)
            // Views get recycled, so a row that WAS the playing one and
            // just got reused for a different song must have its title
            // color reset - otherwise the red would "stick" to whichever
            // recycled view happened to hold it, on a random-looking row.
            holder.title.setTextColor(defaultTitleColor(context))
        }
        holder.itemView.setOnClickListener { onClick(position) }
        holder.itemView.setOnLongClickListener { onLongClick(position); true }
    }

    private fun defaultTitleColor(context: android.content.Context): Int {
        val typedValue = android.util.TypedValue()
        context.theme.resolveAttribute(android.R.attr.textColorPrimary, typedValue, true)
        return if (typedValue.resourceId != 0) context.getColor(typedValue.resourceId) else typedValue.data
    }

    override fun getItemCount(): Int = songs.size
}
