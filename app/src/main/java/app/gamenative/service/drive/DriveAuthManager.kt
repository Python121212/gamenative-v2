package app.gamenative.service.drive

import android.content.Context
import com.google.api.client.googleapis.json.GoogleJsonResponseException
import com.google.api.client.googleapis.services.AbstractGoogleClientRequest
import com.google.api.client.http.HttpRequest
import com.google.api.client.http.HttpRequestInitializer
import com.google.api.client.http.javanet.NetHttpTransport
import com.google.api.client.json.gson.GsonFactory
import com.google.api.services.drive.Drive
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import timber.log.Timber

/**
 * Supplies Drive access tokens and builds Drive API clients.
 *
 * Sign-in itself lives in [DriveOAuthClient] (browser + PKCE), which keeps a
 * refresh token per account; this manager caches short-lived access tokens in
 * memory and transparently refreshes them.
 */
object DriveAuthManager {

    private const val CONNECT_TIMEOUT_MS = 60_000
    private const val READ_TIMEOUT_MS = 0

    private data class CachedToken(val token: String, val expiresAt: Long)

    private val cache = ConcurrentHashMap<String, CachedToken>()

    /** True when the OAuth client (ID + secret) is configured. */
    fun isConfigured(context: Context): Boolean = DriveOAuthClient.isConfigured(context)

    /** Drops a cached access token (e.g. after a 401 from Drive). */
    fun invalidateToken(email: String) {
        cache.remove(email)
    }

    /**
     * Returns an access token for [account], refreshing it when expired.
     *
     * @return the token, or null when the account has to sign in again.
     */
    suspend fun accessToken(
        context: Context,
        account: DriveAccount,
        forceRefresh: Boolean = false,
    ): String? {
        val now = System.currentTimeMillis()
        if (!forceRefresh) {
            cache[account.email]?.let { cached ->
                if (cached.expiresAt > now) return cached.token
            }
        }
        return try {
            val tokens = DriveOAuthClient.refresh(context, account)
            cache[account.email] = CachedToken(tokens.accessToken, tokens.expiresAt)
            tokens.accessToken
        } catch (e: Exception) {
            Timber.w(e, "DriveAuthManager: token refresh failed for ${account.email}")
            null
        }
    }

    /** Blocking variant used by the Drive client's request initializer. */
    private fun accessTokenBlocking(context: Context, account: DriveAccount): String? {
        val now = System.currentTimeMillis()
        cache[account.email]?.let { cached ->
            if (cached.expiresAt > now) return cached.token
        }
        return runBlocking { accessToken(context, account) }
    }

    /** Builds a Drive service bound to [account]. */
    fun driveService(context: Context, account: DriveAccount): Drive {
        val appContext = context.applicationContext
        val initializer = HttpRequestInitializer { request: HttpRequest ->
            val token = accessTokenBlocking(appContext, account)
            if (token != null) {
                request.headers.authorization = "Bearer $token"
            }
            request.connectTimeout = CONNECT_TIMEOUT_MS
            // Large uploads may idle for a while; the uploader handles retries.
            request.readTimeout = READ_TIMEOUT_MS
        }

        return Drive.Builder(
            NetHttpTransport(),
            GsonFactory.getDefaultInstance(),
            initializer,
        )
            .setApplicationName("GameNative Drive SSD")
            .build()
    }

    /** Returns the Drive service, or null when the account cannot be used. */
    suspend fun driveServiceOrNull(context: Context, account: DriveAccount): Drive? =
        withContext(Dispatchers.IO) {
            try {
                if (accessToken(context, account) == null) return@withContext null
                driveService(context, account)
            } catch (e: Exception) {
                Timber.w(e, "DriveAuthManager: could not build Drive service for ${account.email}")
                null
            }
        }

    /**
     * Executes a Drive API request, refreshing the token once on 401.
     * Returns null on failure instead of throwing.
     */
    suspend fun <T> execute(
        request: AbstractGoogleClientRequest<T>,
        email: String? = null,
    ): T? = withContext(Dispatchers.IO) {
        try {
            request.execute()
        } catch (e: GoogleJsonResponseException) {
            if (e.statusCode == 401 && email != null) {
                invalidateToken(email)
                try {
                    request.execute()
                } catch (retry: Exception) {
                    Timber.w(retry, "DriveAuthManager: Drive request failed after token refresh")
                    null
                }
            } else {
                Timber.w(e, "DriveAuthManager: Drive request failed (${e.statusCode})")
                null
            }
        } catch (e: IOException) {
            Timber.w(e, "DriveAuthManager: Drive request failed")
            null
        } catch (e: Exception) {
            Timber.w(e, "DriveAuthManager: Drive request failed")
            null
        }
    }
}
