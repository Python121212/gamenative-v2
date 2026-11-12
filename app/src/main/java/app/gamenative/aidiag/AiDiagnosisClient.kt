package app.gamenative.aidiag

import app.gamenative.BuildConfig
import app.gamenative.utils.CrashFingerprint
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import timber.log.Timber

/**
 * 任意の AI 診断。OpenAI 互換の chat completions エンドポイントを想定する。
 *
 * - API キーが無ければ**何もしない**（ルールベース診断だけで動く）。
 * - 送るのは [LogDigest] を通したダイジェストと、コンテナの構成だけ。生ログやパスは送らない。
 * - 応答は [AiDiagnosisParser] で検証し、許可リスト外の対処は捨てる。
 */
object AiDiagnosisClient {

    private const val TAG = "AiDiagnosis"

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(25, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .build()
    }

    val isConfigured: Boolean
        get() = BuildConfig.AI_DIAGNOSTICS_API_KEY.isNotBlank() && BuildConfig.AI_DIAGNOSTICS_ENDPOINT.isNotBlank()

    private val systemPrompt: String
        get() = buildString {
            appendLine("あなたは Android 上で Windows ゲームを動かす環境の障害解析者です。")
            appendLine("与えられたログの抜粋から原因を推定し、次の行指向フォーマットのみで答えてください。")
            appendLine("SUMMARY: 何が起きたかを日本語 1 文で")
            appendLine("CAUSE: 推定原因を日本語 1 文で")
            appendLine("FIX: <scope>|<key>|<value>|<理由>  (0〜2 行)")
            appendLine("CONFIDENCE: 0.0〜1.0")
            appendLine("指定できる scope/key は次のとおりで、これ以外は書かないでください。")
            append(DiagnosisWhitelist.allowedSummary)
        }

    private fun userPrompt(target: DiagnosisTarget, fingerprint: CrashFingerprint.Fingerprint?, digest: String): String =
        buildString {
            appendLine("## 実行環境")
            appendLine("variant=${target.containerVariant} wine=${target.wineVersion} emulator=${target.emulator}")
            appendLine("dxwrapper=${target.dxwrapper} graphicsDriver=${target.graphicsDriver}")
            appendLine("box64=${target.box64Version} fexcore=${target.fexcoreVersion} adrenotoolsTurnip=${target.adrenotoolsTurnip}")
            appendLine("## フィンガープリント")
            if (fingerprint == null) {
                appendLine("(なし)")
            } else {
                appendLine("component=${fingerprint.component} kind=${fingerprint.kind} code=${fingerprint.code}")
                if (fingerprint.sample.isNotEmpty()) appendLine("sample=" + fingerprint.sample.joinToString(" / "))
            }
            appendLine("## ログ（個人情報は除去済み）")
            appendLine(digest.ifBlank { "(なし)" })
        }

    /** 内部で JSON を組み立てる（テストは [AiDiagnosisParser] 側で行う）。 */
    fun buildRequestBody(target: DiagnosisTarget, fingerprint: CrashFingerprint.Fingerprint?, digest: String): String {
        val messages = JSONArray()
        messages.put(JSONObject().put("role", "system").put("content", systemPrompt))
        messages.put(JSONObject().put("role", "user").put("content", userPrompt(target, fingerprint, digest)))
        return JSONObject()
            .put("model", BuildConfig.AI_DIAGNOSTICS_MODEL)
            .put("temperature", 0.1)
            .put("max_tokens", 400)
            .put("messages", messages)
            .toString()
    }

    /** 失敗しても例外を投げない（診断は「あると嬉しい」機能で、起動を妨げてはいけない）。 */
    suspend fun diagnose(
        target: DiagnosisTarget,
        fingerprint: CrashFingerprint.Fingerprint?,
        digest: String,
    ): Diagnosis? {
        if (!isConfigured) return null
        if (fingerprint == null && digest.isBlank()) return null
        return withContext(Dispatchers.IO) {
            try {
                val body = buildRequestBody(target, fingerprint, digest)
                val request = Request.Builder()
                    .url(BuildConfig.AI_DIAGNOSTICS_ENDPOINT)
                    .addHeader("Authorization", "Bearer ${BuildConfig.AI_DIAGNOSTICS_API_KEY}")
                    .addHeader("Content-Type", "application/json")
                    .post(body.toRequestBody("application/json".toMediaType()))
                    .build()
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        Timber.tag(TAG).w("AI 診断に失敗: HTTP %d", response.code)
                        return@use null
                    }
                    val payload = response.body?.string().orEmpty()
                    val content = extractContent(payload) ?: return@use null
                    AiDiagnosisParser.parse(
                        text = content,
                        fingerprintHash = fingerprint?.hash ?: "digest",
                        component = fingerprint?.component.orEmpty(),
                        kind = fingerprint?.kind.orEmpty(),
                        code = fingerprint?.code.orEmpty(),
                    )
                }
            } catch (error: Throwable) {
                Timber.tag(TAG).w(error, "AI 診断に失敗（ルールベースの結果を使います）")
                null
            }
        }
    }

    /** OpenAI 互換レスポンス（`choices[0].message.content`）から本文を取り出す。 */
    private fun extractContent(payload: String): String? = try {
        JSONObject(payload)
            .optJSONArray("choices")
            ?.optJSONObject(0)
            ?.optJSONObject("message")
            ?.optString("content")
            ?.takeIf { it.isNotBlank() }
    } catch (error: Throwable) {
        Timber.tag(TAG).w(error, "AI の応答を解析できません")
        null
    }
}
