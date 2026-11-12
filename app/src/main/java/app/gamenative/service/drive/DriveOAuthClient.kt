package app.gamenative.service.drive

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Base64
import app.gamenative.PrefManager
import app.gamenative.utils.Net
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.security.MessageDigest
import java.security.SecureRandom
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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
 * Minimal OAuth 2.0 (authorization code + PKCE) client for Google Drive.
 *
 * Why not the Google Sign-In SDK? The SDK's Android client requires an
 * Android OAuth client bound to the *signing certificate*, so every rebuild
 * with a different debug key stops working and sharing an APK between people
 * needs one client registration per build.
 *
 * This flow instead uses a **desktop/installed-app** OAuth client with a
 * loopback redirect (`http://127.0.0.1:<random port>`). That means:
 *
 *  - one client ID works for everybody, regardless of signing key
 *  - the user just picks a Google account in the browser (no code typing)
 *  - the app keeps a refresh token, so uploads continue in the background
 */
object DriveOAuthClient {

    private const val AUTH_ENDPOINT = "https://accounts.google.com/o/oauth2/v2/auth"
    private const val TOKEN_ENDPOINT = "https://oauth2.googleapis.com/token"
    private const val USERINFO_ENDPOINT = "https://openidconnect.googleapis.com/v1/userinfo"
    private const val SCOPE = "openid email profile https://www.googleapis.com/auth/drive.file"
    private const val LOGIN_TIMEOUT_MS = 180_000
    private const val TOKEN_MARGIN_MS = 60_000L
    private const val ASSET_NAME = "drive_oauth.json"

    private val json = Json { ignoreUnknownKeys = true }

    @Volatile
    private var assetConfig: Pair<String, String>? = null

    /** Reads the OAuth client baked into the build (generated asset), if any. */
    private fun bakedConfig(context: Context): Pair<String, String> {
        assetConfig?.let { return it }
        val loaded = try {
            context.assets.open(ASSET_NAME).bufferedReader().use { it.readText() }
                .let { json.parseToJsonElement(it).jsonObject }
                .let { obj ->
                    val id = obj["client_id"]?.jsonPrimitive?.content.orEmpty()
                    val secret = obj["client_secret"]?.jsonPrimitive?.content.orEmpty()
                    id to secret
                }
        } catch (e: Exception) {
            "" to ""
        }
        assetConfig = loaded
        return loaded
    }

    /** Client ID configured in-app, otherwise the one baked into the build. */
    fun clientId(context: Context): String =
        PrefManager.driveOAuthClientId.ifBlank { bakedConfig(context).first }

    /** Client secret configured in-app, otherwise the one baked into the build. */
    fun clientSecret(context: Context): String =
        PrefManager.driveOAuthClientSecret.ifBlank { bakedConfig(context).second }

    /** True when a client ID and secret are available. */
    fun isConfigured(context: Context): Boolean =
        clientId(context).isNotBlank() && clientSecret(context).isNotBlank()

    fun saveConfig(clientId: String, clientSecret: String) {
        PrefManager.driveOAuthClientId = clientId.trim()
        PrefManager.driveOAuthClientSecret = clientSecret.trim()
    }

    /**
     * Runs the full browser sign-in flow.
     *
     * Opens the Google account chooser in the browser and waits for the
     * loopback redirect, so the user only has to tap their account.
     */
    suspend fun signIn(context: Context): DriveTokens {
        val clientId = clientId(context)
        val clientSecret = clientSecret(context)
        if (clientId.isBlank() || clientSecret.isBlank()) {
            throw DriveOAuthException("OAuth client is not configured")
        }

        var tokens = authorize(context, clientId, clientSecret, promptConsent = false)
        if (tokens.refreshToken.isNullOrBlank()) {
            // Google only returns a refresh token when consent is granted
            // explicitly, so ask once more with prompt=consent.
            tokens = authorize(context, clientId, clientSecret, promptConsent = true)
        }
        val email = tokens.email ?: throw DriveOAuthException("Could not read the Google account")
        return tokens.copy(email = email)
    }

    /** Exchanges a stored refresh token for a fresh access token. */
    suspend fun refresh(context: Context, account: DriveAccount): DriveTokens = withContext(Dispatchers.IO) {
        val refreshToken = account.refreshToken
            ?: throw DriveOAuthException("No refresh token for ${account.email}")
        val clientId = clientId(context)
        val clientSecret = clientSecret(context)
        if (clientId.isBlank() || clientSecret.isBlank()) {
            throw DriveOAuthException("OAuth client is not configured")
        }

        val form = FormBody.Builder()
            .add("client_id", clientId)
            .add("client_secret", clientSecret)
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

    /** Signs out locally: revokes the token and forgets it. */
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
        clientId: String,
        clientSecret: String,
        promptConsent: Boolean,
    ): DriveTokens = withContext(Dispatchers.IO) {
        val verifier = randomUrlSafe(64)
        val challenge = base64Url(sha256(verifier))
        val state = randomUrlSafe(24)

        val server = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
        server.soTimeout = LOGIN_TIMEOUT_MS
        val redirectUri = "http://127.0.0.1:${server.localPort}"

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

            val code = awaitRedirect(server, state)
            exchangeCode(clientId, clientSecret, code, redirectUri, verifier)
        } finally {
            try {
                server.close()
            } catch (_: Exception) {
                // already closed
            }
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

    /** Waits for the loopback redirect and returns the authorization code. */
    private fun awaitRedirect(server: ServerSocket, expectedState: String): String {
        val deadline = System.currentTimeMillis() + LOGIN_TIMEOUT_MS
        while (System.currentTimeMillis() < deadline) {
            val socket: Socket = try {
                server.accept()
            } catch (e: SocketTimeoutException) {
                break
            } catch (e: Exception) {
                break
            }

            socket.use { client ->
                val requestLine = try {
                    BufferedReader(InputStreamReader(client.getInputStream(), Charsets.UTF_8)).readLine()
                } catch (e: Exception) {
                    null
                } ?: return@use

                val path = requestLine.split(" ").getOrNull(1) ?: "/"
                if (!path.startsWith("/?")) {
                    // Browsers also request /favicon.ico and similar.
                    respond(client, 404, "Not found")
                    return@use
                }

                val uri = Uri.parse("http://127.0.0.1$path")
                val error = uri.getQueryParameter("error")
                val code = uri.getQueryParameter("code")
                val state = uri.getQueryParameter("state")

                when {
                    error != null -> {
                        respond(client, 200, page("ログインがキャンセルされました", "アプリに戻ってやり直してください。"))
                        throw DriveOAuthException(error)
                    }
                    code == null -> respond(client, 400, page("ログインに失敗しました", "認証コードを受け取れませんでした。"))
                    state != expectedState -> {
                        respond(client, 400, page("ログインに失敗しました", "セキュリティ検証に失敗しました。"))
                        throw DriveOAuthException("state mismatch")
                    }
                    else -> {
                        respond(client, 200, page("ログイン完了", "アプリに戻ってください。このタブは閉じて構いません。"))
                        return code
                    }
                }
            }
        }
        throw DriveOAuthException("Sign-in timed out")
    }

    private suspend fun exchangeCode(
        clientId: String,
        clientSecret: String,
        code: String,
        redirectUri: String,
        verifier: String,
    ): DriveTokens = withContext(Dispatchers.IO) {
        val form = FormBody.Builder()
            .add("client_id", clientId)
            .add("client_secret", clientSecret)
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

    private fun respond(socket: Socket, status: Int, body: String) {
        try {
            val bytes = body.toByteArray(Charsets.UTF_8)
            val header = buildString {
                append("HTTP/1.1 $status OK\r\n")
                append("Content-Type: text/html; charset=utf-8\r\n")
                append("Content-Length: ${bytes.size}\r\n")
                append("Connection: close\r\n\r\n")
            }
            socket.getOutputStream().apply {
                write(header.toByteArray(Charsets.UTF_8))
                write(bytes)
                flush()
            }
        } catch (e: Exception) {
            Timber.w(e, "DriveOAuthClient: could not answer the browser")
        }
    }

    private fun page(title: String, message: String): String = """
        <!doctype html><html lang="ja"><head><meta charset="utf-8">
        <meta name="viewport" content="width=device-width,initial-scale=1">
        <title>$title</title></head>
        <body style="font-family:sans-serif;padding:32px;text-align:center;color:#111">
        <h2>$title</h2><p>$message</p></body></html>
    """.trimIndent()

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
