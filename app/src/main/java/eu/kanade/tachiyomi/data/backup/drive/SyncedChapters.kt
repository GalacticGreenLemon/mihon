package eu.kanade.tachiyomi.data.backup.drive

import android.content.Context
import java.io.File

/**
 * The downloaded chapters that were on the device and on Google Drive after the last upload run,
 * as "source/manga/chapter.cbz". Comparing against it tells chapters deleted on the device apart
 * from chapters this device never had, so only the former get deleted from Drive.
 *
 * Tied to the downloads folder: picking a different storage location starts a fresh record,
 * instead of treating every chapter in the old folder as deleted.
 */
class SyncedChapters(val downloadsDir: String, val chapters: Set<String>) {

    companion object {
        private const val FILE_NAME = "google_drive_synced_chapters.txt"

        fun load(context: Context, downloadsDir: String): SyncedChapters {
            val lines = File(context.filesDir, FILE_NAME).takeIf { it.isFile }?.readLines().orEmpty()
            val chapters = if (lines.firstOrNull() == downloadsDir) lines.drop(1).toSet() else emptySet()
            return SyncedChapters(downloadsDir, chapters)
        }

        fun save(context: Context, downloadsDir: String, chapters: Set<String>) {
            val file = File(context.filesDir, FILE_NAME)
            val tmp = File(context.filesDir, "$FILE_NAME.tmp")
            tmp.writeText((listOf(downloadsDir) + chapters.sorted()).joinToString("\n"))
            tmp.renameTo(file)
        }
    }
}
