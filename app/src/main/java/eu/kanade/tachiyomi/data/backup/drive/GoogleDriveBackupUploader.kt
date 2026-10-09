package eu.kanade.tachiyomi.data.backup.drive

import android.app.PendingIntent
import android.content.Context
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.Scope
import com.hippo.unifile.UniFile
import dev.zacsweers.metro.Inject
import eu.kanade.tachiyomi.network.NetworkHelper
import eu.kanade.tachiyomi.network.awaitSuccess
import kotlinx.coroutines.tasks.await
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import tachiyomi.domain.backup.service.BackupPreferences

/**
 * Uploads backup files to a folder in the user's Google Drive.
 *
 * Uses the `drive.file` scope, so the app can only see files and folders it created itself.
 */
@Inject
class GoogleDriveBackupUploader(
    private val context: Context,
    private val networkHelper: NetworkHelper,
    private val backupPreferences: BackupPreferences,
) {

    private val client get() = networkHelper.client

    /**
     * Requests Drive access. Returns null if access is already granted, or a [PendingIntent]
     * that has to be launched from an activity so the user can pick an account and give consent.
     */
    suspend fun requestAuthorization(): PendingIntent? {
        val result = Identity.getAuthorizationClient(context).authorize(authorizationRequest()).await()
        return if (result.hasResolution()) result.pendingIntent else null
    }

    suspend fun upload(file: UniFile) {
        val token = getAccessToken()
        val folderId = getOrCreateFolder(token)
        uploadFile(token, folderId, file)
        deleteOldBackups(token, folderId)
    }

    private suspend fun getAccessToken(): String {
        val result = Identity.getAuthorizationClient(context).authorize(authorizationRequest()).await()
        if (result.hasResolution()) {
            throw DriveNotAuthorizedException()
        }
        return result.accessToken ?: throw DriveNotAuthorizedException()
    }

    private suspend fun getOrCreateFolder(token: String): String {
        backupPreferences.googleDriveFolderId.get().takeIf { it.isNotEmpty() }?.let { id ->
            // Make sure the folder still exists and wasn't trashed
            val url = "$FILES_URL/$id".toHttpUrl().newBuilder()
                .addQueryParameter("fields", "id,trashed")
                .build()
            val exists = runCatching {
                client.newCall(Request.Builder().url(url).authorized(token).build()).awaitSuccess().use {
                    !JSONObject(it.body.string()).optBoolean("trashed")
                }
            }.getOrDefault(false)
            if (exists) return id
        }

        val metadata = JSONObject()
            .put("name", FOLDER_NAME)
            .put("mimeType", FOLDER_MIME_TYPE)
            .toString()
            .toRequestBody(JSON_MEDIA_TYPE)
        val request = Request.Builder()
            .url("$FILES_URL?fields=id")
            .authorized(token)
            .post(metadata)
            .build()
        val id = client.newCall(request).awaitSuccess().use { JSONObject(it.body.string()).getString("id") }
        backupPreferences.googleDriveFolderId.set(id)
        return id
    }

    private suspend fun uploadFile(token: String, folderId: String, file: UniFile) {
        val bytes = context.contentResolver.openInputStream(file.uri)!!.use { it.readBytes() }
        val metadata = JSONObject()
            .put("name", file.name)
            .put("parents", JSONArray().put(folderId))
            .toString()

        val body = MultipartBody.Builder()
            .setType("multipart/related".toMediaType())
            .addPart(metadata.toRequestBody(JSON_MEDIA_TYPE))
            .addPart(bytes.toRequestBody(BACKUP_MEDIA_TYPE))
            .build()

        val request = Request.Builder()
            .url("$UPLOAD_URL?uploadType=multipart&fields=id")
            .authorized(token)
            .post(body)
            .build()
        client.newCall(request).awaitSuccess().close()
    }

    private suspend fun deleteOldBackups(token: String, folderId: String) {
        val keep = backupPreferences.googleDriveMaxBackups.get()
        if (keep <= 0) return

        val url = FILES_URL.toHttpUrl().newBuilder()
            .addQueryParameter("q", "'$folderId' in parents and trashed = false")
            .addQueryParameter("orderBy", "createdTime desc")
            .addQueryParameter("fields", "files(id,name)")
            .addQueryParameter("pageSize", "1000")
            .build()
        val files = client.newCall(Request.Builder().url(url).authorized(token).build()).awaitSuccess().use {
            val array = JSONObject(it.body.string()).getJSONArray("files")
            List(array.length()) { i -> array.getJSONObject(i).getString("id") }
        }

        files.drop(keep).forEach { id ->
            val request = Request.Builder().url("$FILES_URL/$id").authorized(token).delete().build()
            runCatching { client.newCall(request).awaitSuccess().close() }
        }
    }

    private fun authorizationRequest() = AuthorizationRequest.builder()
        .setRequestedScopes(listOf(Scope(DRIVE_FILE_SCOPE)))
        .build()

    private fun Request.Builder.authorized(token: String) = header("Authorization", "Bearer $token")

    companion object {
        private const val DRIVE_FILE_SCOPE = "https://www.googleapis.com/auth/drive.file"
        private const val FILES_URL = "https://www.googleapis.com/drive/v3/files"
        private const val UPLOAD_URL = "https://www.googleapis.com/upload/drive/v3/files"
        private const val FOLDER_NAME = "Mihon backups"
        private const val FOLDER_MIME_TYPE = "application/vnd.google-apps.folder"
        private val JSON_MEDIA_TYPE = "application/json; charset=UTF-8".toMediaType()
        private val BACKUP_MEDIA_TYPE = "application/octet-stream".toMediaType()
    }
}

class DriveNotAuthorizedException : Exception("Google Drive access was revoked, reconnect it in settings")
