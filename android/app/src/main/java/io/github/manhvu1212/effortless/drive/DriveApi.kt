package io.github.manhvu1212.effortless.drive

import android.content.Context
import android.content.Intent
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.AuthorizationResult
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.Scope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.io.IOException
import kotlin.coroutines.coroutineContext

class NotAuthorizedException : IOException("Google Drive access has not been granted")

/** Read-only access to the user's Drive through Play services; no refresh tokens are kept by the app. */
class DriveAuth(private val context: Context) {
    private val client get() = Identity.getAuthorizationClient(context)

    private val request = AuthorizationRequest.builder()
        .setRequestedScopes(listOf(Scope(SCOPE)))
        .build()

    /** May carry a pending intent: the consent screen the user still has to go through. */
    suspend fun authorize(): AuthorizationResult = client.authorize(request).await()

    fun resultFromIntent(data: Intent?): AuthorizationResult = client.getAuthorizationResultFromIntent(data)

    @Volatile
    private var cached: Pair<String, Long>? = null

    /**
     * An access token without any UI, or null when the user has to grant access first. Kept for
     * 45 minutes (Google's tokens last an hour): asking Play services for each request is slow.
     */
    suspend fun token(): String? {
        cached?.let { (token, at) -> if (System.currentTimeMillis() - at < TOKEN_TTL_MS) return token }
        val token = runCatching { authorize() }.getOrNull()?.takeIf { !it.hasResolution() }?.accessToken
        cached = token?.let { it to System.currentTimeMillis() }
        return token
    }

    /** Drops the cached token after the server rejected it. */
    fun invalidate() {
        cached = null
    }

    companion object {
        const val SCOPE = "https://www.googleapis.com/auth/drive.readonly"
        private const val TOKEN_TTL_MS = 45 * 60 * 1000L
    }
}

data class RemoteEntry(val id: String, val path: String, val size: Long, val md5: String?)

/** The few Drive v3 calls the app needs, over plain HTTPS. */
class DriveApi(private val auth: DriveAuth, private val http: OkHttpClient = OkHttpClient()) {

    suspend fun findFolder(name: String): String? {
        val escaped = name.replace("\\", "\\\\").replace("'", "\\'")
        val json = get(
            "files",
            "q" to "name = '$escaped' and mimeType = '$FOLDER' and trashed = false",
            "fields" to "files(id,name)",
            "pageSize" to "10",
        )
        return json.getJSONArray("files").optJSONObject(0)?.getString("id")
    }

    /** Every file under [folderId], with paths relative to it; folders are listed in parallel. */
    suspend fun listTree(folderId: String): List<RemoteEntry> = coroutineScope {
        val limit = Semaphore(PARALLEL)
        suspend fun walk(id: String, prefix: String): List<RemoteEntry> {
            val (files, folders) = limit.withPermit { listFolder(id, prefix) }
            val nested = folders.map { (childId, path) -> async { walk(childId, "$path/") } }.awaitAll()
            return files + nested.flatten()
        }
        walk(folderId, "")
    }

    /** The files of one folder and its subfolders as (id, path) pairs. */
    private suspend fun listFolder(id: String, prefix: String): Pair<List<RemoteEntry>, List<Pair<String, String>>> {
        val out = mutableListOf<RemoteEntry>()
        val folders = mutableListOf<Pair<String, String>>()
        run {
            var pageToken: String? = null
            do {
                val params = mutableListOf(
                    "q" to "'$id' in parents and trashed = false",
                    "fields" to "nextPageToken,files(id,name,mimeType,size,md5Checksum)",
                    "pageSize" to "1000",
                )
                pageToken?.let { params += "pageToken" to it }
                val json = get("files", *params.toTypedArray())
                val files = json.getJSONArray("files")
                for (i in 0 until files.length()) {
                    val f = files.getJSONObject(i)
                    val path = prefix + f.getString("name")
                    if (f.getString("mimeType") == FOLDER) {
                        folders += f.getString("id") to path
                    } else {
                        out += RemoteEntry(f.getString("id"), path, f.optString("size", "0").toLong(), f.optString("md5Checksum").ifEmpty { null })
                    }
                }
                pageToken = json.optString("nextPageToken").ifEmpty { null }
            } while (pageToken != null)
        }
        return out to folders
    }

    /** Writes to a ".part" file first, so an existing [dest] is always complete. */
    suspend fun download(id: String, dest: File, onBytes: suspend (Long) -> Unit = {}) = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(mediaUrl(id)).header("Authorization", "Bearer ${token()}").build()
        http.newCall(request).execute().use { response ->
            if (response.code == 401) {
                auth.invalidate()
                throw NotAuthorizedException()
            }
            if (!response.isSuccessful) throw IOException("Drive download failed: HTTP ${response.code}")
            dest.parentFile?.mkdirs()
            val part = File(dest.parentFile, dest.name + ".part")
            part.outputStream().use { out ->
                val input = response.body!!.byteStream()
                val buffer = ByteArray(64 * 1024)
                var total = 0L
                while (true) {
                    coroutineContext.ensureActive()
                    val n = input.read(buffer)
                    if (n < 0) break
                    out.write(buffer, 0, n)
                    total += n
                    onBytes(total)
                }
            }
            if (!part.renameTo(dest)) {
                dest.delete()
                if (!part.renameTo(dest)) throw IOException("Cannot move ${part.name} into place")
            }
        }
    }

    private suspend fun token(): String = auth.token() ?: throw NotAuthorizedException()

    private suspend fun get(path: String, vararg params: Pair<String, String>): JSONObject = withContext(Dispatchers.IO) {
        val url = "$BASE/$path".toHttpUrl().newBuilder().apply { params.forEach { (k, v) -> addQueryParameter(k, v) } }.build()
        val request = Request.Builder().url(url).header("Authorization", "Bearer ${token()}").build()
        http.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (response.code == 401 || response.code == 403) {
                auth.invalidate()
                throw NotAuthorizedException()
            }
            if (!response.isSuccessful) throw IOException("Drive HTTP ${response.code}: ${body.take(200)}")
            JSONObject(body)
        }
    }

    companion object {
        private const val BASE = "https://www.googleapis.com/drive/v3"
        private const val FOLDER = "application/vnd.google-apps.folder"
        const val PARALLEL = 8
        const val HOST = "www.googleapis.com"

        fun mediaUrl(id: String): String = "$BASE/files/$id?alt=media"
    }
}
