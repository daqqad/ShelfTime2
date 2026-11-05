package kaf.audiobookshelfwearos.app

import android.app.Activity
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.widget.Toast
import com.fasterxml.jackson.core.json.JsonReadFeature
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import kaf.audiobookshelfwearos.BuildConfig
import kaf.audiobookshelfwearos.app.data.Library
import kaf.audiobookshelfwearos.app.data.LibraryItem
import kaf.audiobookshelfwearos.app.data.User
import kaf.audiobookshelfwearos.app.data.UserMediaProgress
import kaf.audiobookshelfwearos.app.userdata.UserDataManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import org.json.JSONObject
import timber.log.Timber
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit

data class SyncResult(
    val successCount: Int,
    val failureCount: Int,
    val errors: List<String>
)

class ApiHandler(private val context: Context) {
    companion object {
        private val timeout: Long = if (BuildConfig.DEBUG) 3 else 5
        private val sharedClient: OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(timeout, TimeUnit.SECONDS)
            .readTimeout(timeout, TimeUnit.SECONDS)
            .writeTimeout(timeout, TimeUnit.SECONDS)
            .build()

        private val sharedJacksonMapper: ObjectMapper =
            ObjectMapper().enable(JsonReadFeature.ALLOW_UNESCAPED_CONTROL_CHARS.mappedFeature())
    }

    private val client get() = sharedClient
    private val jacksonMapper get() = sharedJacksonMapper
    private val userDataManager = UserDataManager(context)
    private val db = (context.applicationContext as MainApp).database

    // Retry configuration
    private val maxRetries = 1
    private val baseDelayMs = 1000L

    var shouldShowErrorToast = true

    private fun getRequest(endPoint: String) =
        Request.Builder().url(userDataManager.getCompleteAddress() + endPoint)
            .addHeader("Authorization", "Bearer ${userDataManager.token}").build()

    suspend fun getAllLibraries(): List<Library> {
        return withContext(Dispatchers.IO) {
            try {
                val request = getRequest("/api/libraries")
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        showToast(response.code.toString())
                        return@use listOf()
                    }
                    val responseBody = response.body?.string()
                    val jsonResponse = responseBody?.let { JSONObject(it) }
                    val libraries = jsonResponse?.optJSONArray("libraries") ?: return@use listOf()
                    return@use jacksonMapper.readValue(libraries.toString())
                }
            } catch (e: Exception) {
                Timber.w(e, "getAllLibraries failed")
                return@withContext listOf()
            }
        }
    }

    suspend fun getCover(id: String): Bitmap? {
        return withContext(Dispatchers.IO) {
            try {
                val request = getRequest("/api/items/$id/cover?width=200")
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        Timber.d("code: " + response.code)
                        return@use null
                    }
                    val body = response.body ?: return@use null
                    return@use BitmapFactory.decodeStream(body.byteStream())
                }
            } catch (e: Exception) {
                Timber.w(e, "Handled cover error for $id")
                null
            }
        }
    }

    suspend fun getItem(id: String, suppressToast: Boolean = false): LibraryItem? {
        return withContext(Dispatchers.IO) {
            try {
                val request = getRequest("/api/items/$id?expanded=1&include=progress")
                Timber.d("request = " + request)
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        if (!suppressToast) showToast(response.code.toString())
                        return@use null
                    }
                    val responseBody = response.body?.string() ?: return@use null
                    val libraryItem = jacksonMapper.readValue<LibraryItem>(responseBody)
                    val localLibraryItem = db.libraryItemDao().getLibraryItemById(id)
                    localLibraryItem?.let {
                        if (it.userProgress.lastUpdate > libraryItem.userProgress.lastUpdate) {
                            libraryItem.userProgress = localLibraryItem.userProgress
                        }
                    }

                    return@use libraryItem
                }
            } catch (e: SocketTimeoutException) {
                Timber.w("Timeout fetching item $id")
                return@withContext null
            } catch (e: Exception) {
                Timber.w(e, "Handled item error for $id")
                if (!suppressToast) {
                    showToast(e.message ?: "Connection error")
                }
                null
            }
        }
    }

    suspend fun getLibraryItems(id: String): List<LibraryItem> {
        return withContext(Dispatchers.IO) {
            try {
                val request = getRequest("/api/libraries/$id/items?sort=updatedAt")
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        showToast(response.code.toString())
                        return@use emptyList()
                    }
                    val responseBody = response.body?.string() ?: return@use emptyList()
                    val jsonResponse = JSONObject(responseBody)
                    val results = jsonResponse.optJSONArray("results") ?: return@use emptyList()
                    Timber.d(results.length().toString())
                    val items: List<LibraryItem> =
                        jacksonMapper.readValue<List<LibraryItem>>(results.toString()).reversed()
                    return@use items
                }
            } catch (e: Exception) {
                Timber.w(e, "getLibraryItems failed for library $id")
                emptyList()
            }
        }
    }

    /**
     * Enhanced updateProgress with retry logic
     * @return Progress is now up to date
     */
    suspend fun updateProgress(userMediaProgress: UserMediaProgress, retryCount: Int = 0): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                if (userDataManager.offlineMode) return@withContext false
                val serverItem = getItem(userMediaProgress.libraryItemId, suppressToast = true) ?: return@withContext false

                serverItem.let {
                    if (serverItem.userProgress.lastUpdate > userMediaProgress.lastUpdate) {
                        Timber.d("Progress on server is more recent. Not uploading")
                        serverItem.userProgress.toUpload = false
                        db.libraryItemDao().insertLibraryItem(serverItem)
                        return@withContext true
                    }

                    Timber.d("Uploading progress... (attempt ${retryCount + 1})")
                    val success = uploadProgress(userMediaProgress)

                    if (!success && retryCount < maxRetries) {
                        val delay = baseDelayMs * (1 shl retryCount) // Exponential backoff
                        Timber.d("Upload failed, retrying in ${delay}ms")
                        delay(delay)
                        return@withContext updateProgress(userMediaProgress, retryCount + 1)
                    }

                    return@withContext success
                }
            } catch (e: Exception) {
                Timber.w(e, "Failed to update progress (attempt ${retryCount + 1})")
            }

            return@withContext false
        }
    }

    /**
     * Batch sync all pending progress items
     */
    suspend fun syncAllPendingProgress(): SyncResult {
        return withContext(Dispatchers.IO) {
            val pendingItems = db.libraryItemDao().getItemsWithPendingSync()
            var successCount = 0
            var failureCount = 0
            val errors = mutableListOf<String>()
            
            Timber.d("Starting batch sync of ${pendingItems.size} pending items")
            
            for (item in pendingItems) {
                try {
                    val success = updateProgress(item.userProgress)
                    if (success) {
                        successCount++
                        // Mark as synced in database
                        db.libraryItemDao().markProgressAsSynced(item.id)
                        Timber.d("Successfully synced progress for: ${item.title}")
                    } else {
                        failureCount++
                        errors.add("Failed to sync ${item.title}")
                        Timber.w("Failed to sync progress for: ${item.title}")
                    }
                } catch (e: Exception) {
                    failureCount++
                    errors.add("Error syncing ${item.title}: ${e.message}")
                    Timber.e(e, "Error syncing progress for: ${item.title}")
                }
            }
            
            Timber.d("Batch sync completed: $successCount successful, $failureCount failed")
            SyncResult(successCount, failureCount, errors)
        }
    }

    private suspend fun uploadProgress(userMediaProgress: UserMediaProgress): Boolean {
        val jsonBody = JSONObject().apply {
            put("currentTime", userMediaProgress.currentTime)
            put("lastUpdate", userMediaProgress.lastUpdate)
        }

        val requestBody =
            RequestBody.create("application/json".toMediaTypeOrNull(), jsonBody.toString())
        val url =
            userDataManager.getCompleteAddress() + "/api/me/progress/" + userMediaProgress.libraryItemId
        val request = Request.Builder().url(url).patch(requestBody)
            .addHeader("Authorization", "Bearer ${userDataManager.token}").build()

        val res = client.newCall(request).execute().use { response ->
            if (response.isSuccessful) {
                val responseBody = response.body?.string()
                userMediaProgress.toUpload = false
                CoroutineScope(Dispatchers.IO).launch {
                    insertLibraryItemToDB(userMediaProgress)
                }
                Timber.d("Progress uploaded. Response: $responseBody")
                return@use true
            } else {
                Timber.w("Error: ${response.code}")
                return@use false
            }
        }
        return res
    }

    private suspend fun insertLibraryItemToDB(userMediaProgress: UserMediaProgress) {
        db.libraryItemDao().getLibraryItemById(userMediaProgress.libraryItemId)?.let {
            it.userProgress = userMediaProgress
            db.libraryItemDao().insertLibraryItem(it)
        }
    }


    suspend fun login(): User {
        return withContext(Dispatchers.IO) {
            val jsonBody = JSONObject().apply {
                put("username", userDataManager.login)
                put("password", userDataManager.password)
            }

            val requestBody =
                RequestBody.create("application/json".toMediaTypeOrNull(), jsonBody.toString())

            try {
                val request = Request.Builder().url(userDataManager.getCompleteAddress() + "/login")
                    .post(requestBody).addHeader("Content-Type", "application/json").build()

                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        showToast(response.code.toString())
                        return@use User()
                    }
                    val responseBody = response.body?.string()
                    val jsonResponse = responseBody?.let { JSONObject(it) }
                    val user = jsonResponse?.getJSONObject("user")
                    return@use jacksonMapper.readValue<User>(user.toString())
                }
            } catch (e: SocketTimeoutException) {
                if (userDataManager.token.isNotEmpty()) return@withContext User(
                    token = userDataManager.token,
                    id = userDataManager.userId,
                    username = userDataManager.login
                )
                else {
                    showToast("Connection problem")
                    return@withContext User()
                }
            } catch (e: Exception) {
                e.printStackTrace()
                e.message?.let { showToast(it) }
                return@withContext User()
            }
        }
    }

    private fun showToast(text: String) {
        if (shouldShowErrorToast)
            if (context is Activity) context.runOnUiThread {
                Toast.makeText(
                    context, text, Toast.LENGTH_SHORT
                ).show()
            }
    }
}
