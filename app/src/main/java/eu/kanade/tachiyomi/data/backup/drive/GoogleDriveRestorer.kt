package eu.kanade.tachiyomi.data.backup.drive

import android.content.Context
import android.net.Uri
import dev.zacsweers.metro.Inject
import eu.kanade.tachiyomi.extension.ExtensionManager
import eu.kanade.tachiyomi.extension.util.ExtensionLoader
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import java.io.File

/**
 * Brings things uploaded by [GoogleDriveBackupUploader] and [GoogleDriveSyncWorker] back onto the device.
 * Downloaded chapters are handled by [GoogleDriveRestoreWorker], since that can take a long time.
 */
@Inject
class GoogleDriveRestorer(
    private val context: Context,
    private val drive: GoogleDriveClient,
    private val extensionManager: ExtensionManager,
) {

    private val cacheDir get() = File(context.cacheDir, "google_drive").apply { mkdirs() }

    /** Backups on Drive, newest first. */
    suspend fun listBackups(): List<DriveFile> {
        return drive.listChildren(drive.rootFolder()).filter { !it.isFolder && it.name.endsWith(".tachibk") }
    }

    /** Downloads a backup and returns a uri that the normal restore screen can open. */
    suspend fun downloadBackup(backup: DriveFile): Uri {
        cacheDir.listFiles()?.filter { it.extension == "tachibk" }?.forEach { it.delete() }
        val file = File(cacheDir, backup.name)
        file.outputStream().use { drive.download(backup.id, it) }
        return Uri.fromFile(file)
    }

    /**
     * Downloads the extensions that aren't installed and hands them to the installer, which may
     * ask for confirmation for each one. Has to run while the app is in the foreground.
     *
     * @return the number of extensions sent to the installer.
     */
    suspend fun installMissingExtensions(): Int {
        val folderId = drive.findFolder(EXTENSIONS_FOLDER, drive.rootFolder()) ?: return 0

        var started = 0
        drive.listChildren(folderId).forEach { file ->
            val pkgName = APK_NAME_REGEX.matchEntire(file.name)?.groupValues?.get(1) ?: return@forEach
            if (ExtensionLoader.getExtensionPackageInfoFromPkgName(context, pkgName) != null) return@forEach

            try {
                val apk = File(cacheDir, "$pkgName.apk")
                apk.outputStream().use { drive.download(file.id, it) }
                if (extensionManager.installExtensionFile(apk)) started++
            } catch (e: DriveNotAuthorizedException) {
                throw e
            } catch (e: Exception) {
                logcat(LogPriority.ERROR, e) { "Failed to restore ${file.name} from Google Drive" }
            }
        }
        return started
    }

    companion object {
        const val EXTENSIONS_FOLDER = "Extensions"
        const val DOWNLOADS_FOLDER = "Downloads"

        // <package>_v<version>.apk, as named by GoogleDriveSyncWorker
        private val APK_NAME_REGEX = """(.+)_v[^_]+\.apk""".toRegex()
    }
}
