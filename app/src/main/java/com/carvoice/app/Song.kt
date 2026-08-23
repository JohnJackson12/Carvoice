package com.carvoice.app

import android.net.Uri

/** One playable track, regardless of whether it came from MediaStore (the
 * device's whole music library) or a folder added via "Add Music Folder"
 * (Storage Access Framework). uri is always something MediaPlayer can play
 * directly via setDataSource(context, uri). */
data class Song(
    val uri: Uri,
    val title: String,
    val artist: String = "",
)
