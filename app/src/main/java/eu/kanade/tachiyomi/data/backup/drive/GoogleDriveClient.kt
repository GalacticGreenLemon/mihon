package eu.kanade.tachiyomi.data.backup.drive

import android.app.PendingIntent
import android.content.Context
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.Scope
import dev.zacsweers.metro.Inject
import eu.kanade.tachiyomi.network.NetworkHelper
import eu.kanade.tachiyomi.network.awaitSuccess
import kotlinx.coroutines.tasks.await
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okio.BufferedSink
import okio.source
import org.json.JSONArray
import org.json.JSONObject
import tachiyomi.domain.backup.service.BackupPreferences
import java.io.InputStream
import kotlin.time.Duration.Companion.minutes

/**
 * Minimal Google Drive REST client.
 *
 * Uses the `drive.file` scope, so the app can only see files and folders it created itself.
 * Everything is stored below a "Mihon backups" folder in the root of the user's Drive.
 */
@Inject
class GoogleDriveClient(
    private val context: Context,
    networkHelper: NetworkHelper,
    private val backupPreferences: BackupPreferences,
) {

    // Uploads can be big (downloaded chapters), so don't cap the total call duration
    private val client: OkHttpClient = networkHelper.client.newBuilder()
        .callTimeout(0.minutes)
        .readTimeout(2.minutes)
        .writeTimeout(2.minutes)
        .build()

    /**
     * Requests Drive access. Returns null if access is already granted, or a [PendingIntent]
     * that has to be launched from an activity so the user can pick an account and give consent.
     */
    suspend fun requestAuthorization(): PendingIntent? {
        val result = Identity.getAuthorizationClient(context).authorize(authorizationRequest()).await()
        return if (result.hasResolution()) result.pendingIntent else null
    }

    /** The "Mihon backups" folder, created if needed. */
    suspend fun rootFolder(): String {
        backupPreferences.googleDriveFolderId.get().takeIf { it.isNotEmpty() }?.let { id ->
            // Make sure the folder still exists and wasn't trashed
            val url = "$FILES_URL/$id".toHttpUrl().newBuilder()
                .addQueryParameter("fields", "id,trashed")
                .build()
            val exists = runCatching {
                !execute(Request.Builder().url(url)).getBoolean("trashed")
            }.getOrDefault(false)
            if (exists) return id
        }

        val id = createFolder(ROOT_FOLDER_NAME, parentId = null)
        backupPreferences.googleDriveFolderId.set(id)
        return id
    }

    suspend fun findOrCreateFolder(name: String, parentId: String): String {
        return listChildren(parentId).firstOrNull { it.name == name && it.isFolder }?.id
            ?: createFolder(name, parentId)
    }

    /** Lists the files and folders directly inside [parentId], newest first. */
    suspend fun listChildren(parentId: String): List<DriveFile> {
        val files = mutableListOf<DriveFile>()
        var pageToken: String? = null
        do {
            val url = FILES_URL.toHttpUrl().newBuilder()
                .addQueryParameter("q", "'$parentId' in parents and trashed = false")
                .addQueryParameter("orderBy", "createdTime desc")
                .addQueryParameter("fields", "nextPageToken,files(id,name,mimeType,size)")
                .addQueryParameter("pageSize", "1000")
                .apply { pageToken?.let { addQueryParameter("pageToken", it) } }
                .build()
            val json = execute(Request.Builder().url(url))
            val array = json.getJSONArray("files")
            repeat(array.length()) { i ->
                val file = array.getJSONObject(i)
                files += DriveFile(
                    id = file.getString("id"),
                    name = file.getString("name"),
                    isFolder = file.getString("mimeType") == FOLDER_MIME_TYPE,
                    size = file.optString("size").toLongOrNull(),
                )
            }
            pageToken = json.optString("nextPageToken").takeIf { it.isNotEmpty() }
        } while (pageToken != null)
        return files
    }

    /**
     * Uploads a file using a resumable upload session, which (unlike a multipart upload)
     * is meant for files of any size. The content is streamed, not loaded into memory.
     */
    suspend fun upload(
        name: String,
        parentId: String,
        contentLength: Long,
        open: () -> InputStream,
    ) {
        val metadata = JSONObject()
            .put("name", name)
            .put("parents", JSONArray().put(parentId))
            .toString()
            .toRequestBody(JSON_MEDIA_TYPE)
        val sessionRequest = Request.Builder()
            .url("$UPLOAD_URL?uploadType=resumable")
            .header("X-Upload-Content-Type", BINARY_MEDIA_TYPE.toString())
            .header("X-Upload-Content-Length", contentLength.toString())
            .post(metadata)
            .authorized()
            .build()
        val sessionUrl = client.newCall(sessionRequest).awaitSuccess().use { it.header("Location") }
            ?: error("Google Drive didn't return an upload URL")

        val body = object : RequestBody() {
            override fun contentType(): MediaType = BINARY_MEDIA_TYPE
            override fun contentLength() = contentLength
            override fun writeTo(sink: BufferedSink) {
                open().source().use { sink.writeAll(it) }
            }
        }
        client.newCall(Request.Builder().url(sessionUrl).put(body).build()).awaitSuccess().close()
    }

    suspend fun delete(id: String) {
        client.newCall(Request.Builder().url("$FILES_URL/$id").delete().authorized().build()).awaitSuccess().close()
    }

    private suspend fun createFolder(name: String, parentId: String?): String {
        val metadata = JSONObject()
            .put("name", name)
            .put("mimeType", FOLDER_MIME_TYPE)
            .apply { parentId?.let { put("parents", JSONArray().put(it)) } }
            .toString()
            .toRequestBody(JSON_MEDIA_TYPE)
        return execute(Request.Builder().url("$FILES_URL?fields=id").post(metadata)).getString("id")
    }

    private suspend fun execute(builder: Request.Builder): JSONObject {
        return client.newCall(builder.authorized().build()).awaitSuccess().use { JSONObject(it.body.string()) }
    }

    /** Fetches a token for every request, since a long upload run can outlive a single token. */
    private suspend fun Request.Builder.authorized(): Request.Builder {
        val result = Identity.getAuthorizationClient(context).authorize(authorizationRequest()).await()
        val token = result.accessToken.takeUnless { result.hasResolution() }
            ?: throw DriveNotAuthorizedException()
        return header("Authorization", "Bearer $token")
    }

    private fun authorizationRequest() = AuthorizationRequest.builder()
        .setRequestedScopes(listOf(Scope(DRIVE_FILE_SCOPE)))
        .build()

    companion object {
        private const val DRIVE_FILE_SCOPE = "https://www.googleapis.com/auth/drive.file"
        private const val FILES_URL = "https://www.googleapis.com/drive/v3/files"
        private const val UPLOAD_URL = "https://www.googleapis.com/upload/drive/v3/files"
        private const val ROOT_FOLDER_NAME = "Mihon backups"
        private const val FOLDER_MIME_TYPE = "application/vnd.google-apps.folder"
        private val JSON_MEDIA_TYPE = "application/json; charset=UTF-8".toMediaType()
        private val BINARY_MEDIA_TYPE = "application/octet-stream".toMediaType()
    }
}

data class DriveFile(
    val id: String,
    val name: String,
    val isFolder: Boolean,
    val size: Long?,
)

class DriveNotAuthorizedException : Exception("Google Drive access was revoked, reconnect it in settings")
