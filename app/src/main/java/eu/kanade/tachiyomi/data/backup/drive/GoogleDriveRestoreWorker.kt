package eu.kanade.tachiyomi.data.backup.drive

import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkerParameters
import com.hippo.unifile.UniFile
import dev.zacsweers.metro.Inject
import eu.kanade.tachiyomi.data.backup.BackupNotifier
import eu.kanade.tachiyomi.data.download.DownloadCache
import eu.kanade.tachiyomi.data.download.Downloader
import eu.kanade.tachiyomi.data.notification.Notifications
import eu.kanade.tachiyomi.util.system.cancelNotification
import eu.kanade.tachiyomi.util.system.setForegroundSafely
import eu.kanade.tachiyomi.util.system.workManager
import logcat.LogPriority
import mihon.app.di.AppGraph
import mihon.core.metro.metroGraph
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.storage.service.StorageManager
import tachiyomi.i18n.MR
import kotlin.coroutines.cancellation.CancellationException

/**
 * Downloads chapters uploaded by [GoogleDriveSyncWorker] back into the local downloads folder.
 * Chapters that are already on the device, either as a folder or as a CBZ, are skipped.
 */
class GoogleDriveRestoreWorker(private val context: Context, workerParams: WorkerParameters) :
    CoroutineWorker(context, workerParams) {

    private val graph: AppGraph = context.metroGraph()

    @Inject
    private lateinit var drive: GoogleDriveClient

    @Inject
    private lateinit var storageManager: StorageManager

    @Inject
    private lateinit var downloadCache: DownloadCache

    @Inject
    private lateinit var notifier: BackupNotifier

    private var failures = 0
    private var lastError: String? = null

    override suspend fun doWork(): Result {
        graph.inject(this)

        val downloadsDir = storageManager.getDownloadsDirectory() ?: return Result.failure()

        setForegroundSafely()

        return try {
            val folderId = drive.findFolder(GoogleDriveRestorer.DOWNLOADS_FOLDER, drive.rootFolder())
            if (folderId != null) restoreDownloads(folderId, downloadsDir)
            if (failures > 0) {
                notifier.showGoogleDriveUploadError(
                    context.stringResource(MR.strings.google_drive_sync_failed, failures, lastError.orEmpty()),
                )
            }
            Result.success()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, e) { "Restoring downloads from Google Drive failed" }
            notifier.showGoogleDriveUploadError(e.message)
            Result.failure()
        } finally {
            // Let the app pick up the new chapters
            downloadCache.invalidateCache()
            context.cancelNotification(Notifications.ID_GOOGLE_DRIVE_PROGRESS)
        }
    }

    private suspend fun restoreDownloads(folderId: String, downloadsDir: UniFile) {
        val mangaFolders = drive.listChildren(folderId)
            .filter { it.isFolder }
            .flatMap { source -> drive.listChildren(source.id).filter { it.isFolder }.map { source to it } }

        mangaFolders.forEachIndexed { index, (source, manga) ->
            notifier.showGoogleDriveProgress(manga.name, index, mangaFolders.size, download = true)

            attempt {
                val remoteChapters = drive.listChildren(manga.id).filter { !it.isFolder }
                val localDir = downloadsDir.findFile(source.name)?.findFile(manga.name)
                val localNames = localDir?.listFiles().orEmpty().mapNotNullTo(HashSet()) { it.name }

                val missing = remoteChapters.filter {
                    it.name !in localNames && it.name.removeSuffix(".cbz") !in localNames
                }
                if (missing.isEmpty()) return@attempt

                val mangaDir = (downloadsDir.createDirectory(source.name) ?: error("Couldn't create ${source.name}"))
                    .createDirectory(manga.name) ?: error("Couldn't create ${manga.name}")

                missing.forEach { chapter -> attempt { restoreChapter(chapter, mangaDir) } }
            }
        }
    }

    private suspend fun restoreChapter(chapter: DriveFile, mangaDir: UniFile) {
        // Same pattern as the downloader: write to a temporary name, then rename once complete
        val tmpName = chapter.name + Downloader.TMP_DIR_SUFFIX
        mangaDir.findFile(tmpName)?.delete()
        val tmp = mangaDir.createFile(tmpName) ?: error("Couldn't create $tmpName")
        try {
            context.contentResolver.openOutputStream(tmp.uri)!!.use { drive.download(chapter.id, it) }
            if (!tmp.renameTo(chapter.name)) error("Couldn't rename $tmpName")
        } catch (e: Exception) {
            tmp.delete()
            throw e
        }
    }

    private suspend fun attempt(block: suspend () -> Unit) {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: DriveNotAuthorizedException) {
            throw e
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, e) { "Google Drive download failed" }
            failures++
            lastError = e.message ?: e::class.simpleName
        }
    }

    override suspend fun getForegroundInfo(): ForegroundInfo {
        return ForegroundInfo(
            Notifications.ID_GOOGLE_DRIVE_PROGRESS,
            notifier.showGoogleDriveProgress(download = true).build(),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            } else {
                0
            },
        )
    }

    companion object {
        private const val TAG = "GoogleDriveRestore"

        fun start(context: Context) {
            val request = OneTimeWorkRequestBuilder<GoogleDriveRestoreWorker>()
                .addTag(TAG)
                .setConstraints(Constraints(requiredNetworkType = NetworkType.CONNECTED))
                .build()
            context.workManager.enqueueUniqueWork(TAG, ExistingWorkPolicy.KEEP, request)
        }
    }
}
