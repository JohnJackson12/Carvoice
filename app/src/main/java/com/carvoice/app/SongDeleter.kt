package com.carvoice.app

import android.app.RecoverableSecurityException
import android.content.Context
import android.content.IntentSender
import android.os.Build
import android.provider.MediaStore
import androidx.documentfile.provider.DocumentFile
import java.io.File

/**
 * Single shared engine for deleting - and undoing the delete of - a
 * song's underlying file. Used by BOTH the manual delete paths in
 * MainActivity (the now-playing trash icon, the long-press "Delete"
 * menu) AND the voice "<wake> delete" / "<wake> undo" commands in
 * VoiceService, so the two can never disagree about what "delete"
 * actually does, or leave a file half-deleted the way the old
 * per-Activity code here used to (see the API-29 branch below for
 * exactly what that bug was).
 *
 * Three storage sources this app deals with, each with its own delete
 * AND undo story:
 *
 *  - A plain file:// path (added via FolderBrowserActivity - only
 *    reachable with MANAGE_EXTERNAL_STORAGE already granted, which is
 *    what let this file be added in the first place). No OS trash
 *    concept exists for a raw file, so this app manages its own trash
 *    folder and moves the file there instead of deleting it outright -
 *    undo just moves it back. Same idea as the Windows app's own
 *    trash_folder in config.py/library.py, ported to wherever Android
 *    actually lets this app write.
 *
 *  - A MediaStore content:// item (the common case - scanning the
 *    device's whole music library) on API 30+ (R+):
 *    MediaStore.createTrashRequest() - the real, OS-supported
 *    soft-delete. Per Android's own docs for createTrashRequest /
 *    createDeleteRequest, "the requested operation will have completely
 *    finished before this activity result is delivered" - so one user
 *    tap on the system dialog and the OS itself performs the trash;
 *    there is nothing left to retry afterward. Calling it again with
 *    trashed=false is the real, OS-backed "undo" - the file was never
 *    actually removed from disk, just hidden, matching the Windows
 *    app's trash+undo behavior almost exactly.
 *
 *  - The same MediaStore content:// item on exactly API 29 (Q), where
 *    createTrashRequest doesn't exist yet: a direct
 *    ContentResolver.delete() throws RecoverableSecurityException for a
 *    file this app didn't create (which is nearly always true - this
 *    app never writes music files itself). THE ACTUAL BUG THIS FIXES:
 *    granting that recoverable action does NOT perform the delete for
 *    you - Android's own docs for RecoverableSecurityException say apps
 *    observing RESULT_OK "may choose to immediately retry their
 *    operation". The previous code here treated RESULT_OK as "already
 *    deleted" (it just updated the in-app list and showed a "Deleted."
 *    toast) and never retried the actual delete() - so the system
 *    dialog would show, the user would tap Allow, and the underlying
 *    file would silently survive untouched, reappearing on the next
 *    scan. That's the "even manual deletion is not permitted" symptom:
 *    it visually looked like nothing happened. This retries the real
 *    delete() once consent is granted. Permanent - no undo available on
 *    this exact API level (Q has no trash API).
 *
 *  - A SAF tree document (added via "Add Music Folder" with write
 *    permission already granted up front) never throws
 *    RecoverableSecurityException - the permission grant at folder-add
 *    time IS the consent. No OS trash concept exists for an arbitrary
 *    SAF document, so this is a permanent delete, same as before this
 *    fix.
 */
object SongDeleter {

    /** Result of [delete] or [undoLast]. */
    sealed class Outcome {
        /** Done immediately. [undoable] says whether [undoLast] can bring
         * it back (this app's own trash folder, or MediaStore's real
         * trash on R+) vs. a plain permanent delete (API 29 MediaStore
         * items, and SAF documents - neither of which this app can
         * safely relocate on its own). */
        data class Done(val song: Song, val undoable: Boolean) : Outcome()

        /** The OS requires one-tap user consent before this can proceed -
         * a hard scoped-storage requirement, not something this app can
         * skip or pre-approve. Whoever calls [delete]/[undoLast] needs a
         * real Activity to show this (startIntentSenderForResult) - see
         * MainActivity's resolveOutcome() and VoiceService's
         * consentResolver for the two places that happens. Call
         * [onResult] with the outcome of that UI to get the final,
         * terminal [Outcome]. */
        data class NeedsConsent(
            val intentSender: IntentSender,
            val onResult: (approved: Boolean) -> Outcome,
        ) : Outcome()

        data class Failed(val message: String) : Outcome()

        /** Nothing loaded/playing to delete, or nothing left to undo. */
        object NothingLoaded : Outcome()
    }

    private data class Trashed(
        val song: Song,
        // Set only for the app-managed trash-folder branch (raw file://
        // paths); null means "MediaStore's own trash" (R+ content:// items).
        val movedToPath: String?,
    )

    // A stack, not a single slot, so repeated "<wake> undo" keeps
    // restoring further back - matches the Windows app's own
    // _last_deleted stack in library.py.
    private val trashedStack = mutableListOf<Trashed>()

    fun hasUndo(): Boolean = trashedStack.isNotEmpty()

    private fun appTrashDir(context: Context): File =
        File(context.getExternalFilesDir(null), "trash").apply { mkdirs() }

    /** True if deleting this song right now would land somewhere
     * undoable. Purely informational (e.g. for a confirmation dialog) -
     * [delete] itself figures out the real branch independently. */
    fun wouldBeUndoable(song: Song): Boolean = when (song.uri.scheme) {
        "file" -> true
        else -> Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
            song.uri.toString().startsWith(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI.toString())
    }

    /** Attempts to delete/trash [song]. Call from the main thread - every
     * branch here is quick, and the consent flow (when one is needed)
     * has to run on the main thread regardless. */
    fun delete(context: Context, song: Song): Outcome =
        if (song.uri.scheme == "file") deleteRawFile(context, song) else deleteContentUri(context, song)

    private fun deleteRawFile(context: Context, song: Song): Outcome {
        val path = song.uri.path ?: return Outcome.Failed("Couldn't delete - no file path")
        val src = File(path)
        if (!src.exists()) return Outcome.Failed("Couldn't delete - the file is already gone")
        val dest = File(appTrashDir(context), "${System.currentTimeMillis()}_${src.name}")
        return try {
            if (src.renameTo(dest)) {
                trashedStack.add(Trashed(song, dest.absolutePath))
                Outcome.Done(song, undoable = true)
            } else {
                Outcome.Failed("Couldn't delete - the file may be read-only")
            }
        } catch (e: Exception) {
            Outcome.Failed(e.message ?: "Couldn't delete")
        }
    }

    private fun deleteContentUri(context: Context, song: Song): Outcome {
        val resolver = context.contentResolver
        val isMediaStoreUri = song.uri.toString().startsWith(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI.toString())

        if (!isMediaStoreUri) {
            // A SAF tree document - write permission already granted when
            // the folder was added, so no consent dialog is expected here.
            return try {
                val rows = try { resolver.delete(song.uri, null, null) } catch (e: Exception) { 0 }
                if (rows > 0) return Outcome.Done(song, undoable = false)
                // Some SAF providers return 0 instead of throwing when a
                // plain ContentResolver.delete() isn't the right path for
                // them - DocumentFile's own delete() is the more reliable
                // call for a tree-document URI specifically.
                val doc = DocumentFile.fromSingleUri(context, song.uri)
                if (doc != null && doc.delete()) Outcome.Done(song, undoable = false)
                else Outcome.Failed("Couldn't delete - the file may be read-only or already gone")
            } catch (e: Exception) {
                Outcome.Failed(e.message ?: "Couldn't delete")
            }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val pending = MediaStore.createTrashRequest(resolver, listOf(song.uri), true)
            return Outcome.NeedsConsent(pending.intentSender) { approved ->
                if (approved) {
                    trashedStack.add(Trashed(song, movedToPath = null))
                    Outcome.Done(song, undoable = true)
                } else {
                    Outcome.Failed("Delete cancelled")
                }
            }
        }

        return try {
            val rows = resolver.delete(song.uri, null, null)
            if (rows > 0) Outcome.Done(song, undoable = false)
            else Outcome.Failed("Couldn't delete - the file may be read-only or already gone")
        } catch (e: RecoverableSecurityException) {
            Outcome.NeedsConsent(e.userAction.actionIntent.intentSender) { approved ->
                if (!approved) {
                    Outcome.Failed("Delete cancelled")
                } else {
                    // The retry this whole file exists for - see the class
                    // doc's API-29 branch above.
                    val retried = try { resolver.delete(song.uri, null, null) } catch (e2: Exception) { 0 }
                    if (retried > 0) Outcome.Done(song, undoable = false)
                    else Outcome.Failed("Couldn't delete - the file may be read-only or already gone")
                }
            }
        } catch (e: Exception) {
            Outcome.Failed(e.message ?: "Couldn't delete")
        }
    }

    /** Restores the most recently deleted/trashed song, if any and if it's
     * undoable. Same [Outcome] shape as [delete] - untrashing a
     * MediaStore item on R+ also needs one-tap consent
     * (createTrashRequest(..., trashed = false)). */
    fun undoLast(context: Context): Outcome {
        val entry = trashedStack.lastOrNull() ?: return Outcome.NothingLoaded
        return if (entry.movedToPath != null) {
            val originalPath = entry.song.uri.path
            val trashedFile = File(entry.movedToPath)
            if (originalPath == null || !trashedFile.exists()) {
                trashedStack.removeAt(trashedStack.lastIndex)
                return Outcome.Failed("Nothing to restore")
            }
            val dest = File(originalPath)
            dest.parentFile?.mkdirs()
            if (trashedFile.renameTo(dest)) {
                trashedStack.removeAt(trashedStack.lastIndex)
                Outcome.Done(entry.song, undoable = false)
            } else {
                Outcome.Failed("Couldn't restore the file")
            }
        } else {
            val pending = MediaStore.createTrashRequest(context.contentResolver, listOf(entry.song.uri), false)
            Outcome.NeedsConsent(pending.intentSender) { approved ->
                if (approved) {
                    trashedStack.remove(entry)
                    Outcome.Done(entry.song, undoable = false)
                } else {
                    Outcome.Failed("Restore cancelled")
                }
            }
        }
    }
}
