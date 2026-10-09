package eu.kanade.tachiyomi.data.backup.drive

import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.work.BackoffPolicy
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
import eu.kanade.tachiyomi.data.download.Downloader
import eu.kanade.tachiyomi.data.notification.Notifications
import eu.kanade.tachiyomi.extension.ExtensionManager
import eu.kanade.tachiyomi.extension.util.ExtensionLoader
import eu.kanade.tachiyomi.util.system.cancelNotification
import eu.kanade.tachiyomi.util.system.setForegroundSafely
import eu.kanade.tachiyomi.util.system.workManager
import logcat.LogPriority
import mihon.app.di.AppGraph
import mihon.app.di.appGraph
import mihon.core.metro.metroGraph
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.backup.service.BackupPreferences
import tachiyomi.domain.storage.service.StorageManager
import tachiyomi.i18n.MR
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.coroutines.cancellation.CancellationException

/**
 * Uploads installed extensions and downloaded chapters to Google Drive.
 *
 * Only adds what's missing on Drive, so after the first run it only uploads new things.
 * Layout on Drive:
 * - Remon backups/Extensions/<package>_v<version>.apk
 * - Remon backups/Downloads/<source>/<manga>/<chapter>.cbz (same names as the local downloads folder)
 */
class GoogleDriveSyncWorker(private val context: Context, workerParams: WorkerParameters) :
    CoroutineWorker(context, workerParams) {

    private val graph: AppGraph = context.metroGraph()

    @Inject
    private lateinit var drive: GoogleDriveClient

    @Inject
    private lateinit var backupPreferences: BackupPreferences

    @Inject
    private lateinit var storageManager: StorageManager

    @Inject
    private lateinit var extensionManager: ExtensionManager

    @Inject
    private lateinit var notifier: BackupNotifier

    private var failures = 0
    private var lastError: String? = null

    override suspend fun doWork(): Result {
        graph.inject(this)

        if (!backupPreferences.googleDriveEnabled.get()) return Result.success()

        setForegroundSafely()

        return try {
            val rootId = drive.rootFolder()
            if (backupPreferences.googleDriveUploadExtensions.get()) {
                uploadExtensions(drive.findOrCreateFolder(GoogleDriveRestorer.EXTENSIONS_FOLDER, rootId))
            }
            if (backupPreferences.googleDriveUploadDownloads.get()) {
                uploadDownloads(drive.findOrCreateFolder(GoogleDriveRestorer.DOWNLOADS_FOLDER, rootId))
            }
            if (failures > 0) {
                notifier.showGoogleDriveUploadError(
                    context.stringResource(MR.strings.google_drive_sync_failed, failures, lastError.orEmpty()),
                )
            }
            Result.success()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, e) { "Google Drive sync failed" }
            notifier.showGoogleDriveUploadError(e.message)
            Result.failure()
        } finally {
            context.cancelNotification(Notifications.ID_GOOGLE_DRIVE_PROGRESS)
        }
    }

    private suspend fun uploadExtensions(folderId: String) {
        val extensions = extensionManager.getLoadedExtensions() + extensionManager.getNotLoadedExtensions()
        val existing = drive.listChildren(folderId)

        extensions.forEachIndexed { index, extension ->
            notifier.showGoogleDriveProgress(extension.name, index, extensions.size)

            val fileName = "${extension.pkgName}_v${extension.versionName}.apk"
            if (existing.any { it.name == fileName }) return@forEachIndexed

            attempt {
                val apk = ExtensionLoader.getExtensionPackageInfoFromPkgName(context, extension.pkgName)
                    ?.applicationInfo?.sourceDir
                    ?.let(::File)
                    ?.takeIf { it.canRead() }
                    ?: error("Couldn't find the APK of ${extension.name}")

                drive.upload(fileName, folderId, apk.length()) { apk.inputStream() }

                // Only keep the newest version of each extension
                existing
                    .filter { it.name.startsWith("${extension.pkgName}_v") }
                    .forEach { runCatching { drive.delete(it.id) } }
            }
        }
    }

    private suspend fun uploadDownloads(folderId: String) {
        val downloadsDir = storageManager.getDownloadsDirectory() ?: return
        // Null means the folder couldn't be read (e.g. lost permission), which must not look like "everything deleted"
        val sourceDirs = downloadsDir.listFiles() ?: return

        val mangaDirs = sourceDirs
            .filter { it.isDirectory }
            .flatMap { sourceDir ->
                sourceDir.listFiles().orEmpty()
                    .filter { it.isDirectory }
                    .map { sourceDir to it }
            }

        val record = SyncedChapters.load(context, downloadsDir.uri.toString())
        val onDevice = HashSet<String>()
        val synced = HashSet<String>()

        val sourceFolders = mutableMapOf<String, String>()
        mangaDirs.forEachIndexed { index, (sourceDir, mangaDir) ->
            val chapters = mangaDir.listFiles().orEmpty().filter { it.isChapter() }
            if (chapters.isEmpty()) return@forEachIndexed

            val sourceName = sourceDir.name!!
            val mangaName = mangaDir.name!!
            fun key(chapter: UniFile) = chapterKey(sourceName, mangaName, chapter.cbzName())
            chapters.mapTo(onDevice, ::key)

            notifier.showGoogleDriveProgress(mangaName, index, mangaDirs.size)

            attempt {
                val sourceFolderId = sourceFolders.getOrPut(sourceName) {
                    drive.findOrCreateFolder(sourceName, folderId)
                }
                val mangaFolderId = drive.findOrCreateFolder(mangaName, sourceFolderId)
                val existing = drive.listChildren(mangaFolderId).mapTo(HashSet()) { it.name }

                chapters.forEach { chapter ->
                    if (chapter.cbzName() in existing) {
                        synced += key(chapter)
                    } else {
                        attempt {
                            uploadChapter(chapter, mangaFolderId)
                            synced += key(chapter)
                        }
                    }
                }
            }
        }

        // Chapters that were on the phone at the last run but are gone now were deleted (or read and auto-deleted)
        val deleted = record.chapters - onDevice
        val notDeleted = when {
            // With nothing on the phone at all, assume the folder isn't really readable and try again next time
            onDevice.isEmpty() -> deleted
            backupPreferences.googleDriveMirrorDeletions.get() -> deleteFromDrive(folderId, deleted)
            // Kept on Drive for good, so turning the option on later doesn't delete them after all
            else -> emptySet()
        }

        SyncedChapters.save(context, record.downloadsDir, synced + (record.chapters intersect onDevice) + notDeleted)
    }

    /** Deletes the given chapters from Drive, and returns the ones that couldn't be deleted. */
    private suspend fun deleteFromDrive(folderId: String, chapters: Set<String>): Set<String> {
        val failed = HashSet<String>()
        chapters.groupBy { it.substringBeforeLast('/') }.forEach { (mangaPath, keys) ->
            val (sourceName, mangaName) = mangaPath.split('/', limit = 2)
            try {
                val sourceFolderId = drive.findFolder(sourceName, folderId) ?: return@forEach
                val mangaFolderId = drive.findFolder(mangaName, sourceFolderId) ?: return@forEach
                val names = keys.mapTo(HashSet()) { it.substringAfterLast('/') }

                val files = drive.listChildren(mangaFolderId)
                files.filter { it.name in names }.forEach { drive.delete(it.id) }

                // Don't leave empty folders behind for manga with no chapters left
                if (files.all { it.name in names }) drive.delete(mangaFolderId)
                if (drive.listChildren(sourceFolderId).isEmpty()) drive.delete(sourceFolderId)
            } catch (e: CancellationException) {
                throw e
            } catch (e: DriveNotAuthorizedException) {
                throw e
            } catch (e: Exception) {
                logcat(LogPriority.ERROR, e) { "Failed to delete chapters of $mangaPath from Google Drive" }
                failed += keys
            }
        }
        return failed
    }

    private fun chapterKey(source: String, manga: String, chapter: String) = "$source/$manga/$chapter"

    private suspend fun uploadChapter(chapter: UniFile, folderId: String) {
        if (chapter.isFile) {
            drive.upload(chapter.name!!, folderId, chapter.length()) {
                context.contentResolver.openInputStream(chapter.uri)!!
            }
            return
        }

        // Chapters downloaded as image folders are zipped into a CBZ, which Mihon can read as well
        val tmp = File.createTempFile("drive_upload", ".cbz", context.cacheDir)
        try {
            ZipOutputStream(tmp.outputStream().buffered()).use { zip ->
                chapter.listFiles().orEmpty()
                    .filter { it.isFile }
                    .sortedBy { it.name }
                    .forEach { page ->
                        zip.putNextEntry(ZipEntry(page.name!!))
                        context.contentResolver.openInputStream(page.uri)!!.use { it.copyTo(zip) }
                        zip.closeEntry()
                    }
            }
            drive.upload(chapter.cbzName(), folderId, tmp.length()) { tmp.inputStream() }
        } finally {
            tmp.delete()
        }
    }

    private fun UniFile.isChapter(): Boolean {
        val name = name ?: return false
        if (name.endsWith(Downloader.TMP_DIR_SUFFIX)) return false
        return isDirectory || name.endsWith(".cbz")
    }

    private fun UniFile.cbzName(): String = if (isDirectory) "$name.cbz" else name!!

    /** Runs one upload; a failure is recorded so it doesn't stop the remaining uploads. */
    private suspend fun attempt(block: suspend () -> Unit) {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: DriveNotAuthorizedException) {
            throw e
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, e) { "Google Drive upload failed" }
            failures++
            lastError = e.message ?: e::class.simpleName
        }
    }

    override suspend fun getForegroundInfo(): ForegroundInfo {
        return ForegroundInfo(
            Notifications.ID_GOOGLE_DRIVE_PROGRESS,
            notifier.showGoogleDriveProgress().build(),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            } else {
                0
            },
        )
    }

    companion object {
        private const val TAG = "GoogleDriveSync"

        /** Starts an upload run if extensions or downloads are set to be uploaded. */
        fun start(context: Context) {
            val preferences = context.appGraph.backupPreferences
            val uploadDownloads = preferences.googleDriveUploadDownloads.get()
            if (!preferences.googleDriveUploadExtensions.get() && !uploadDownloads) return

            val networkType = if (uploadDownloads && preferences.googleDriveDownloadsWifiOnly.get()) {
                NetworkType.UNMETERED
            } else {
                NetworkType.CONNECTED
            }
            val request = OneTimeWorkRequestBuilder<GoogleDriveSyncWorker>()
                .addTag(TAG)
                .setConstraints(Constraints(requiredNetworkType = networkType, requiresBatteryNotLow = true))
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 10, TimeUnit.MINUTES)
                .build()
            context.workManager.enqueueUniqueWork(TAG, ExistingWorkPolicy.KEEP, request)
        }
    }
}
