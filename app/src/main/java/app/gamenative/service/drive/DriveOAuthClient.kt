package app.gamenative.service.drive

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Base64
import app.gamenative.utils.Net
import java.security.MessageDigest
import java.security.SecureRandom
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.FormBody
import okhttp3.Request
import timber.log.Timber

/** Thrown when the Google sign-in flow fails, with a user presentable reason. */
class DriveOAuthException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** Converts a completed sign-in into a stored [DriveAccount]. */
fun DriveTokens.toDriveAccount(): DriveAccount = DriveAccount(
    id = DriveAccountStore.newId(),
    email = email ?: "unknown",
    displayName = displayName,
    photoUrl = photoUrl,
    refreshToken = refreshToken,
)

/** Tokens returned by the OAuth flow. */
data class DriveTokens(
    val accessToken: String,
    val expiresAt: Long,
    val refreshToken: String? = null,
    val email: String? = null,
    val displayName: String? = null,
    val photoUrl: String? = null,
)

/**
 * OAuth 2.0 (authorization code + PKCE) client for Google Drive using an
 * **Android** OAuth client.
 *
 * The browser handles the account chooser and redirects back to
 * `com.googleusercontent.apps.<client-id>:/oauth2redirect`, which
 * [DriveOAuthRedirectActivity] receives. Because Android clients are public
 * clients there is no client secret to embed.
 *
 * The client ID must be baked into the build (`GOOGLE_OAUTH_CLIENT_ID` in
 * `local.properties` / Gradle properties / environment): the redirect scheme
 * is derived from it, and the scheme is declared in the manifest at build time.
 */
object DriveOAuthClient {

    private const val AUTH_ENDPOINT = "https://accounts.google.com/o/oauth2/v2/auth"
    private const val TOKEN_ENDPOINT = "https://oauth2.googleapis.com/token"
    private const val USERINFO_ENDPOINT = "https://openidconnect.googleapis.com/v1/userinfo"
    private const val SCOPE = "openid email profile https://www.googleapis.com/auth/drive.file"
    private const val LOGIN_TIMEOUT_MS = 180_000L
    private const val TOKEN_MARGIN_MS = 60_000L
    private const val ASSET_NAME = "drive_oauth.json"
    private const val FALLBACK_SCHEME = "app.gamenative.driveoauth"
    private const val REDIRECT_PATH = "/oauth2redirect"

    private val json = Json { ignoreUnknownKeys = true }

    @Volatile
    private var bakedClientId: String? = null

    /** Pending browser flow, completed by [onRedirect]. */
    private class PendingFlow(val state: String, val redirectUri: String, val verifier: String) {
        val callback = CompletableDeferred<Uri>()
    }

    @Volatile
    private var pending: PendingFlow? = null

    /** Reads the OAuth client baked into the build, if any. */
    private fun bakedClientId(context: Context): String {
        bakedClientId?.let { return it }
        val loaded = try {
            context.assets.open(ASSET_NAME).bufferedReader().use { it.readText() }
                .let { json.parseToJsonElement(it).jsonObject }
                .let { obj -> obj["client_id"]?.jsonPrimitive?.content.orEmpty() }
        } catch (e: Exception) {
            ""
        }
        bakedClientId = loaded
        return loaded
    }

    /** Client ID configured at build time. */
    fun clientId(context: Context): String = bakedClientId(context)

    /** True when a client ID is baked into this build. */
    fun isConfigured(context: Context): Boolean = clientId(context).isNotBlank()

    /** Custom scheme registered in the manifest for this build. */
    fun redirectScheme(context: Context): String {
        val clientId = clientId(context)
        return if (clientId.isBlank()) FALLBACK_SCHEME else "com.googleusercontent.apps.$clientId"
    }

    /** Full redirect URI, e.g. `com.googleusercontent.apps.123-abc:/oauth2redirect`. */
    fun redirectUri(context: Context): String = redirectScheme(context) + ":" + REDIRECT_PATH

    /**
     * Called by [DriveOAuthRedirectActivity] when the browser comes back.
     */
    fun onRedirect(uri: Uri) {
        val flow = pending
        if (flow == null) {
            Timber.w("DriveOAuthClient: redirect received with no sign-in in progress")
            return
        }
        flow.callback.complete(uri)
    }

    /**
     * Runs the browser sign-in flow: the Google account chooser opens and the
     * user only has to tap an account.
     */
    suspend fun signIn(context: Context): DriveTokens {
        if (!isConfigured(context)) {
            throw DriveOAuthException("No OAuth client ID in this build")
        }

        var tokens = authorize(context, promptConsent = false)
        if (tokens.refreshToken.isNullOrBlank()) {
            // Google only hands out a refresh token with an explicit consent.
            tokens = authorize(context, promptConsent = true)
        }
        val email = tokens.email ?: throw DriveOAuthException("Could not read the Google account")
        return tokens.copy(email = email)
    }

    /** Exchanges a stored refresh token for a fresh access token. */
    suspend fun refresh(context: Context, account: DriveAccount): DriveTokens = withContext(Dispatchers.IO) {
        val refreshToken = account.refreshToken
            ?: throw DriveOAuthException("No refresh token for ${account.email}")
        val clientId = clientId(context)
        if (clientId.isBlank()) throw DriveOAuthException("No OAuth client ID in this build")

        val form = FormBody.Builder()
            .add("client_id", clientId)
            .add("refresh_token", refreshToken)
            .add("grant_type", "refresh_token")
            .build()

        val body = post(TOKEN_ENDPOINT, form)
        val accessToken = body["access_token"] ?: throw DriveOAuthException("Token refresh returned no access token")
        val expiresIn = body["expires_in"]?.toLongOrNull() ?: 3600L
        DriveTokens(
            accessToken = accessToken,
            expiresAt = System.currentTimeMillis() + expiresIn * 1000L - TOKEN_MARGIN_MS,
            refreshToken = body["refresh_token"] ?: refreshToken,
            email = account.email,
        )
    }

    /** Revokes the tokens for [account] and forgets them. */
    suspend fun revoke(account: DriveAccount) = withContext(Dispatchers.IO) {
        val token = account.refreshToken ?: return@withContext
        try {
            Net.http.newCall(
                Request.Builder()
                    .url("https://oauth2.googleapis.com/revoke?token=${Uri.encode(token)}")
                    .post(FormBody.Builder().build())
                    .build(),
            ).execute().close()
        } catch (e: Exception) {
            Timber.w(e, "DriveOAuthClient: revoke failed")
        }
    }

    // ------------------------------------------------------------------
    // Internals
    // ------------------------------------------------------------------

    private suspend fun authorize(
        context: Context,
        promptConsent: Boolean,
    ): DriveTokens = withContext(Dispatchers.IO) {
        val clientId = clientId(context)
        val verifier = randomUrlSafe(64)
        val challenge = base64Url(sha256(verifier))
        val state = randomUrlSafe(24)
        val redirectUri = redirectUri(context)

        val flow = PendingFlow(state, redirectUri, verifier)
        pending = flow
        try {
            val prompt = if (promptConsent) "select_account consent" else "select_account"
            val url = Uri.parse(AUTH_ENDPOINT).buildUpon()
                .appendQueryParameter("client_id", clientId)
                .appendQueryParameter("redirect_uri", redirectUri)
                .appendQueryParameter("response_type", "code")
                .appendQueryParameter("scope", SCOPE)
                .appendQueryParameter("code_challenge", challenge)
                .appendQueryParameter("code_challenge_method", "S256")
                .appendQueryParameter("access_type", "offline")
                .appendQueryParameter("prompt", prompt)
                .appendQueryParameter("state", state)
                .appendQueryParameter("include_granted_scopes", "true")
                .build()

            openBrowser(context, url)

            val callback = try {
                withTimeout(LOGIN_TIMEOUT_MS) { flow.callback.await() }
            } catch (e: TimeoutCancellationException) {
                throw DriveOAuthException("Sign-in timed out")
            }

            val error = callback.getQueryParameter("error")
            if (error != null) throw DriveOAuthException(error)

            val code = callback.getQueryParameter("code")
                ?: throw DriveOAuthException("No authorization code in the redirect")
            if (callback.getQueryParameter("state") != state) {
                throw DriveOAuthException("state mismatch")
            }

            exchangeCode(clientId, code, redirectUri, verifier)
        } finally {
            pending = null
        }
    }

    private suspend fun openBrowser(context: Context, url: Uri) = withContext(Dispatchers.Main) {
        val intent = Intent(Intent.ACTION_VIEW, url)
        if (context !is Activity) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try {
            context.startActivity(intent)
        } catch (e: Exception) {
            throw DriveOAuthException("Could not open a browser", e)
        }
    }

    private suspend fun exchangeCode(
        clientId: String,
        code: String,
        redirectUri: String,
        verifier: String,
    ): DriveTokens = withContext(Dispatchers.IO) {
        val form = FormBody.Builder()
            .add("client_id", clientId)
            .add("code", code)
            .add("redirect_uri", redirectUri)
            .add("grant_type", "authorization_code")
            .add("code_verifier", verifier)
            .build()

        val body = post(TOKEN_ENDPOINT, form)
        val accessToken = body["access_token"] ?: throw DriveOAuthException("Token exchange returned no access token")
        val expiresIn = body["expires_in"]?.toLongOrNull() ?: 3600L
        val profile = parseIdToken(body["id_token"]) ?: fetchProfile(accessToken)

        DriveTokens(
            accessToken = accessToken,
            expiresAt = System.currentTimeMillis() + expiresIn * 1000L - TOKEN_MARGIN_MS,
            refreshToken = body["refresh_token"],
            email = profile?.email,
            displayName = profile?.name,
            photoUrl = profile?.picture,
        )
    }

    private class Profile(val email: String?, val name: String?, val picture: String?)

    private fun parseIdToken(idToken: String?): Profile? {
        if (idToken.isNullOrBlank()) return null
        return try {
            val payload = idToken.split(".").getOrNull(1) ?: return null
            val decoded = String(
                Base64.decode(payload, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING),
                Charsets.UTF_8,
            )
            val obj = json.parseToJsonElement(decoded).jsonObject
            Profile(
                email = obj["email"]?.jsonPrimitive?.content,
                name = obj["name"]?.jsonPrimitive?.content,
                picture = obj["picture"]?.jsonPrimitive?.content,
            )
        } catch (e: Exception) {
            Timber.w(e, "DriveOAuthClient: could not parse id_token")
            null
        }
    }

    private fun fetchProfile(accessToken: String): Profile? = try {
        val request = Request.Builder()
            .url(USERINFO_ENDPOINT)
            .header("Authorization", "Bearer $accessToken")
            .build()
        Net.http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                null
            } else {
                val obj = json.parseToJsonElement(response.body?.string().orEmpty()).jsonObject
                Profile(
                    email = obj["email"]?.jsonPrimitive?.content,
                    name = obj["name"]?.jsonPrimitive?.content,
                    picture = obj["picture"]?.jsonPrimitive?.content,
                )
            }
        }
    } catch (e: Exception) {
        Timber.w(e, "DriveOAuthClient: could not fetch profile")
        null
    }

    /** Posts a form to the token endpoint and returns the JSON body. */
    private fun post(url: String, form: FormBody): Map<String, String> {
        val request = Request.Builder().url(url).post(form).build()
        return Net.http.newCall(request).execute().use { response ->
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                val detail = try {
                    json.parseToJsonElement(text).jsonObject["error"]?.jsonPrimitive?.content
                } catch (e: Exception) {
                    null
                }
                throw DriveOAuthException(
                    "Google returned HTTP ${response.code}" + (detail?.let { ": $it" } ?: ""),
                )
            }
            val obj = json.parseToJsonElement(text).jsonObject
            val values = HashMap<String, String>()
            obj.forEach { (key, value) ->
                val primitive = value as? kotlinx.serialization.json.JsonPrimitive ?: return@forEach
                if (primitive is kotlinx.serialization.json.JsonNull) return@forEach
                values[key] = primitive.content
            }
            values
        }
    }

    private fun randomUrlSafe(bytes: Int): String {
        val random = ByteArray(bytes)
        SecureRandom().nextBytes(random)
        return base64Url(random)
    }

    private fun base64Url(bytes: ByteArray): String =
        Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)

    private fun sha256(value: String): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.US_ASCII))
}
