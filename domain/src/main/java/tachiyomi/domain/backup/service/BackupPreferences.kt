package tachiyomi.domain.backup.service

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import tachiyomi.core.common.preference.Preference
import tachiyomi.core.common.preference.PreferenceStore

@Inject
@SingleIn(AppScope::class)
class BackupPreferences(
    preferenceStore: PreferenceStore,
) {

    val backupInterval: Preference<Int> = preferenceStore.getInt("backup_interval", 12)

    val lastAutoBackupTimestamp: Preference<Long> = preferenceStore.getLong(
        Preference.appStateKey("last_auto_backup_timestamp"),
        0L,
    )

    val googleDriveEnabled: Preference<Boolean> = preferenceStore.getBoolean("google_drive_backup_enabled", false)

    val googleDriveUploadExtensions: Preference<Boolean> = preferenceStore.getBoolean(
        "google_drive_upload_extensions",
        false,
    )

    val googleDriveUploadDownloads: Preference<Boolean> = preferenceStore.getBoolean(
        "google_drive_upload_downloads",
        false,
    )

    val googleDriveMirrorDeletions: Preference<Boolean> = preferenceStore.getBoolean(
        "google_drive_mirror_deletions",
        true,
    )

    val googleDriveDownloadsWifiOnly: Preference<Boolean> = preferenceStore.getBoolean(
        "google_drive_downloads_wifi_only",
        true,
    )

    val googleDriveMaxBackups: Preference<Int> = preferenceStore.getInt("google_drive_max_backups", 10)

    val googleDriveFolderId: Preference<String> = preferenceStore.getString(
        Preference.appStateKey("google_drive_folder_id"),
        "",
    )

    val lastGoogleDriveUploadTimestamp: Preference<Long> = preferenceStore.getLong(
        Preference.appStateKey("last_google_drive_upload_timestamp"),
        0L,
    )
}
