package com.carvoice.app

import android.net.Uri

/** One playable track, regardless of whether it came from MediaStore (the
 * device's whole music library) or a folder added via "Add Music Folder"
 * (Storage Access Framework). uri is always something MediaPlayer can play
 * directly via setDataSource(context, uri).
 *
 * [folder] and [folderIndex] describe where the song sits in its OWN
 * source folder, independent of however the song list is currently
 * sorted/filtered for display - e.g. "3" for the 3rd track (alphabetically,
 * within that one folder) inside "Road Trip Mix". These are assigned once
 * at scan time (see MusicLibrary) and displayed as the list's "Song#"
 * column so a track keeps a stable, meaningful number no matter where it
 * lands in a filtered/searched view. */
data class Song(
    val uri: Uri,
    val title: String,
    val artist: String = "",
    val folder: String = "",
    val folderIndex: Int = 0,
)
