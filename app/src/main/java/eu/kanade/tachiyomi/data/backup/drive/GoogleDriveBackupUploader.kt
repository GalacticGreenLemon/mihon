package eu.kanade.tachiyomi.data.backup.drive

import android.content.Context
import com.hippo.unifile.UniFile
import dev.zacsweers.metro.Inject
import tachiyomi.domain.backup.service.BackupPreferences

/**
 * Uploads backup files to the root of the "Remon backups" Drive folder and prunes old ones.
 */
@Inject
class GoogleDriveBackupUploader(
    private val context: Context,
    private val drive: GoogleDriveClient,
    private val backupPreferences: BackupPreferences,
) {

    suspend fun requestAuthorization() = drive.requestAuthorization()

    suspend fun upload(file: UniFile) {
        val folderId = drive.rootFolder()
        drive.upload(file.name!!, folderId, file.length()) {
            context.contentResolver.openInputStream(file.uri)!!
        }
        deleteOldBackups(folderId)
    }

    private suspend fun deleteOldBackups(folderId: String) {
        val keep = backupPreferences.googleDriveMaxBackups.get()
        if (keep <= 0) return

        drive.listChildren(folderId)
            .filter { !it.isFolder && it.name.endsWith(".tachibk") }
            .drop(keep)
            .forEach { runCatching { drive.delete(it.id) } }
    }
}
