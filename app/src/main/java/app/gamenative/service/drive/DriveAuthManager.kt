package app.gamenative.service.drive

import android.accounts.Account
import android.app.Activity
import android.content.Context
import android.content.Intent
import com.google.android.gms.auth.GoogleAuthUtil
import com.google.android.gms.auth.UserRecoverableAuthException
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInAccount
import com.google.android.gms.auth.api.signin.GoogleSignInClient
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.Scope
import com.google.android.gms.tasks.Tasks
import com.google.api.client.googleapis.json.GoogleJsonResponseException
import com.google.api.client.googleapis.services.AbstractGoogleClientRequest
import com.google.api.client.http.HttpRequest
import com.google.api.client.http.HttpRequestInitializer
import com.google.api.client.http.javanet.NetHttpTransport
import com.google.api.client.json.gson.GsonFactory
import com.google.api.services.drive.Drive
import com.google.api.services.drive.DriveScopes
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber

/**
 * Manages Google sign-in and access tokens for the Drive pseudo-SSD.
 *
 * Multiple Google accounts can be registered. Each Drive request resolves a
 * short-lived OAuth access token for the account's email via [GoogleAuthUtil]
 * (AccountManager caching handles refreshes), so no long-lived secrets are
 * stored by the app.
 */
object DriveAuthManager {

    /** OAuth scope granting access to files created by this app. */
    val DRIVE_SCOPE: Scope = Scope(DriveScopes.DRIVE_FILE)

    private const val GOOGLE_ACCOUNT_TYPE = "com.google"

    /** Cache of account email -> access token (tokens last ~1 hour). */
    private val tokenCache = ConcurrentHashMap<String, String>()

    /**
     * Set when Google requires the user to grant the Drive scope again.
     * The UI can launch this intent to finish the consent flow.
     */
    @Volatile
    var pendingConsentIntent: Intent? = null
        private set

    private val signInOptions: GoogleSignInOptions
        get() = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
            .requestEmail()
            .requestProfile()
            .requestScopes(DRIVE_SCOPE)
            .build()

    fun signInClient(context: Context): GoogleSignInClient =
        GoogleSignIn.getClient(context, signInOptions)

    /** Intent that launches the Google account picker / sign-in flow. */
    fun signInIntent(context: Context): Intent = signInClient(context).signInIntent

    /** Converts a completed sign-in result into a [DriveAccount]. */
    fun accountFromSignIn(account: GoogleSignInAccount): DriveAccount {
        val email = account.email ?: "unknown"
        return DriveAccount(
            id = DriveAccountStore.newId(),
            email = email,
            displayName = account.displayName,
            photoUrl = account.photoUrl?.toString(),
        )
    }

    /** Signs out the currently active Google account (allows adding another). */
    suspend fun signOut(context: Context) = withContext(Dispatchers.IO) {
        try {
            Tasks.await(signInClient(context).signOut())
        } catch (e: Exception) {
            Timber.w(e, "DriveAuthManager: sign out failed")
        }
    }

    /** Revokes access for the currently active Google account. */
    suspend fun revokeAccess(context: Context) = withContext(Dispatchers.IO) {
        try {
            Tasks.await(signInClient(context).revokeAccess())
            tokenCache.clear()
        } catch (e: Exception) {
            Timber.w(e, "DriveAuthManager: revoke access failed")
        }
    }

    /**
     * Returns a Drive access token for [email], refreshing when necessary.
     * Must not be called on the main thread.
     *
     * @return the token, or null when the user must grant consent again.
     */
    fun accessToken(context: Context, email: String, forceRefresh: Boolean = false): String? {
        if (!forceRefresh) {
            tokenCache[email]?.let { return it }
        }
        return try {
            val account = Account(email, GOOGLE_ACCOUNT_TYPE)
            val scope = "oauth2:${DriveScopes.DRIVE_FILE}"
            val token = GoogleAuthUtil.getToken(context, account, scope)
            tokenCache[email] = token
            token
        } catch (e: UserRecoverableAuthException) {
            // The user has to re-approve the Drive scope.
            pendingConsentIntent = e.intent
            Timber.w(e, "DriveAuthManager: consent required for $email")
            null
        } catch (e: Exception) {
            Timber.w(e, "DriveAuthManager: could not obtain token for $email")
            null
        }
    }

    /** Drops a cached token (called after a 401 from Drive). */
    fun invalidateToken(email: String) {
        tokenCache.remove(email)
    }

    /** Builds a Drive service bound to [account]'s email. */
    fun driveService(context: Context, account: DriveAccount): Drive {
        val appContext = context.applicationContext
        val initializer = HttpRequestInitializer { request: HttpRequest ->
            val token = accessToken(appContext, account.email)
                ?: accessToken(appContext, account.email, forceRefresh = true)
            if (token != null) {
                request.headers.authorization = "Bearer $token"
            }
            request.connectTimeout = CONNECT_TIMEOUT_MS
            // Large uploads can legitimately idle; let the uploader manage it.
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

    /** Returns the Drive service, or null when the account is not usable. */
    suspend fun driveServiceOrNull(context: Context, account: DriveAccount): Drive? =
        withContext(Dispatchers.IO) {
            try {
                if (accessToken(context, account.email) == null) return@withContext null
                driveService(context, account)
            } catch (e: Exception) {
                Timber.w(e, "DriveAuthManager: could not build Drive service for ${account.email}")
                null
            }
        }

    /**
     * Executes a Drive API request, translating recoverable auth failures.
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

    private const val CONNECT_TIMEOUT_MS = 60_000
    private const val READ_TIMEOUT_MS = 0

    /** Exposed for the consent flow: an [Activity] is required to launch it. */
    fun consentIntent(): Intent? = pendingConsentIntent
}
