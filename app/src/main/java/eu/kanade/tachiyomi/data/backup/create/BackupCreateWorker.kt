package eu.kanade.tachiyomi.data.backup.create

import android.content.Context
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import androidx.core.net.toUri
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.hippo.unifile.UniFile
import dev.zacsweers.metro.Inject
import eu.kanade.tachiyomi.data.backup.BackupNotifier
import eu.kanade.tachiyomi.data.backup.drive.GoogleDriveBackupUploader
import eu.kanade.tachiyomi.data.backup.restore.BackupRestoreWorker
import eu.kanade.tachiyomi.data.notification.Notifications
import eu.kanade.tachiyomi.util.system.cancelNotification
import eu.kanade.tachiyomi.util.system.isRunning
import eu.kanade.tachiyomi.util.system.setForegroundSafely
import eu.kanade.tachiyomi.util.system.workManager
import logcat.LogPriority
import mihon.app.di.AppGraph
import mihon.app.di.appGraph
import mihon.core.metro.metroGraph
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.backup.service.BackupPreferences
import tachiyomi.domain.storage.service.StorageManager
import java.util.concurrent.TimeUnit
import kotlin.time.Clock

class BackupCreateWorker(private val context: Context, workerParams: WorkerParameters) :
    CoroutineWorker(context, workerParams) {

    private val graph: AppGraph = context.metroGraph()

    @Inject
    private lateinit var backupCreatorFactory: BackupCreator.Factory

    @Inject
    private lateinit var storageManager: StorageManager

    @Inject private lateinit var notifier: BackupNotifier

    @Inject
    private lateinit var backupPreferences: BackupPreferences

    @Inject
    private lateinit var googleDriveUploader: GoogleDriveBackupUploader

    override suspend fun doWork(): Result {
        graph.inject(this)

        val isAutoBackup = inputData.getBoolean(IS_AUTO_BACKUP_KEY, true)

        if (isAutoBackup && BackupRestoreWorker.isRunning(context.workManager)) return Result.retry()

        val uri = inputData.getString(LOCATION_URI_KEY)?.toUri()
            ?: getAutomaticBackupLocation()
            ?: return Result.failure()

        setForegroundSafely()

        val options = inputData.getBooleanArray(OPTIONS_KEY)?.let { BackupOptions.fromBooleanArray(it) }
            ?: BackupOptions()

        return try {
            val location = backupCreatorFactory.create(isAutoBackup = isAutoBackup).backup(uri, options)
            if (!isAutoBackup) {
                notifier.showBackupComplete(UniFile.fromUri(context, location.toUri())!!)
            } else if (backupPreferences.googleDriveEnabled.get()) {
                uploadToGoogleDrive(location)
            }
            Result.success()
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, e)
            if (!isAutoBackup) notifier.showBackupError(e.message)
            Result.failure()
        } finally {
            context.cancelNotification(Notifications.ID_BACKUP_PROGRESS)
        }
    }

    private suspend fun uploadToGoogleDrive(location: String) {
        // The local backup already succeeded, so a failed upload shouldn't fail the whole job
        try {
            googleDriveUploader.upload(UniFile.fromUri(context, location.toUri())!!)
            backupPreferences.lastGoogleDriveUploadTimestamp.set(Clock.System.now().toEpochMilliseconds())
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, e) { "Failed to upload backup to Google Drive" }
            notifier.showGoogleDriveUploadError(e.message)
        }
    }

    override suspend fun getForegroundInfo(): ForegroundInfo {
        return ForegroundInfo(
            Notifications.ID_BACKUP_PROGRESS,
            notifier.showBackupProgress().build(),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            } else {
                0
            },
        )
    }

    private fun getAutomaticBackupLocation(): Uri? {
        return storageManager.getAutomaticBackupsDirectory()?.uri
    }

    companion object {
        fun isManualJobRunning(context: Context): Boolean {
            return context.workManager.isRunning(TAG_MANUAL)
        }

        fun setupTask(context: Context, prefInterval: Int? = null) {
            val backupPreferences = context.appGraph.backupPreferences
            val interval = prefInterval ?: backupPreferences.backupInterval.get()
            if (interval > 0) {
                val constraints = Constraints(
                    requiredNetworkType = if (backupPreferences.googleDriveEnabled.get()) {
                        NetworkType.CONNECTED
                    } else {
                        NetworkType.NOT_REQUIRED
                    },
                    requiresBatteryNotLow = true,
                )

                val request = PeriodicWorkRequestBuilder<BackupCreateWorker>(
                    interval.toLong(),
                    TimeUnit.HOURS,
                    10,
                    TimeUnit.MINUTES,
                )
                    .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 10, TimeUnit.MINUTES)
                    .addTag(TAG_AUTO)
                    .setConstraints(constraints)
                    .setInputData(workDataOf(IS_AUTO_BACKUP_KEY to true))
                    .build()

                context.workManager.enqueueUniquePeriodicWork(TAG_AUTO, ExistingPeriodicWorkPolicy.UPDATE, request)
            } else {
                context.workManager.cancelUniqueWork(TAG_AUTO)
            }
        }

        /**
         * Runs an automatic backup right away, which also uploads it to Google Drive if enabled.
         */
        fun startAutoBackupNow(context: Context) {
            val request = OneTimeWorkRequestBuilder<BackupCreateWorker>()
                .addTag(TAG_AUTO_NOW)
                .setInputData(workDataOf(IS_AUTO_BACKUP_KEY to true))
                .build()
            context.workManager.enqueueUniqueWork(TAG_AUTO_NOW, ExistingWorkPolicy.KEEP, request)
        }

        fun startNow(context: Context, uri: Uri, options: BackupOptions) {
            val inputData = workDataOf(
                IS_AUTO_BACKUP_KEY to false,
                LOCATION_URI_KEY to uri.toString(),
                OPTIONS_KEY to options.asBooleanArray(),
            )
            val request = OneTimeWorkRequestBuilder<BackupCreateWorker>()
                .addTag(TAG_MANUAL)
                .setInputData(inputData)
                .build()
            context.workManager.enqueueUniqueWork(TAG_MANUAL, ExistingWorkPolicy.KEEP, request)
        }
    }
}

private const val TAG_AUTO = "BackupCreator"
private const val TAG_MANUAL = "$TAG_AUTO:manual"
private const val TAG_AUTO_NOW = "$TAG_AUTO:now"

private const val IS_AUTO_BACKUP_KEY = "is_auto_backup" // Boolean
private const val LOCATION_URI_KEY = "location_uri" // String
private const val OPTIONS_KEY = "options" // BooleanArray
