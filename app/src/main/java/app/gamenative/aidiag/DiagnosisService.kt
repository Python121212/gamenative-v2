package app.gamenative.aidiag

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import app.gamenative.utils.CrashFingerprint
import com.winlator.container.Container
import com.winlator.core.KeyValueSet
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * ゲームの異常終了を受けて「何が起きたか」を自動で判定する。
 *
 * 流れ:
 * 1. [CrashFingerprint] でゲーム出力からフィンガープリントを作る（既存の仕組み）。
 * 2. [LogDigest] でログを伏字化・圧縮する。
 * 3. [RuleDiagnoser] で端末内判定。確度が高ければそこで確定（ネットワーク不要・無料）。
 * 4. 確度が低く、AI が設定されていれば [AiDiagnosisClient] に問い合わせる。
 * 5. 結果を `files/aidiag/<hash>.json` に保存し、同じ失敗では再診断しない。
 *
 * 起動や終了処理をブロックしない（すべてバックグラウンド、失敗しても投げない）。
 */
object DiagnosisService {

    private const val TAG = "Diagnosis"
    private const val CACHE_TTL_MS = 7L * 24 * 60 * 60 * 1000

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * 異常終了の診断をバックグラウンドで開始する。
     *
     * @param exitReason 例: `exit_1`。フィンガープリントの手がかりになる。
     * @param tail ゲストの標準出力/標準エラーの末尾（[app.gamenative.utils.GuestOutputTail.snapshot]）。
     * @param notify true なら結果を Toast で短く知らせる。
     */
    fun requestDiagnosis(
        context: Context,
        container: Container?,
        exitReason: String?,
        tail: String,
        notify: Boolean = true,
    ) {
        val appContext = context.applicationContext
        scope.launch {
            val diagnosis = runCatching { attempt(appContext, container, exitReason, tail) }
                .onFailure { error -> Timber.tag(TAG).w(error, "診断に失敗しました（ゲームの終了処理は継続します）") }
                .getOrNull()
            if (diagnosis != null && notify) notifyUser(appContext, diagnosis)
        }
    }

    /** 実際の判定。キャッシュ → ルール → （必要なら）AI の順。 */
    private suspend fun attempt(
        context: Context,
        container: Container?,
        exitReason: String?,
        tail: String,
    ): Diagnosis? {
        val target = targetOf(container)
        val fingerprint = CrashFingerprint.extract(tail, exitReason, versionsOf(target))
        val digest = LogDigest.digest(tail)

        readCache(context, fingerprint?.hash)?.let { cached ->
            Timber.tag(TAG).i("キャッシュ済みの診断: %s", cached.summary)
            return cached
        }

        val rule = RuleDiagnoser.diagnose(fingerprint, digest, target)
        val diagnosis = when {
            rule != null && rule.confidence >= RuleDiagnoser.HIGH_CONFIDENCE -> rule
            AiDiagnosisClient.isConfigured -> AiDiagnosisClient.diagnose(target, fingerprint, digest) ?: rule
            else -> rule
        } ?: return null

        Timber.tag(TAG).i(
            "診断 (source=%s conf=%.2f %s/%s/%s): %s",
            diagnosis.source,
            diagnosis.confidence,
            diagnosis.component,
            diagnosis.kind,
            diagnosis.code,
            diagnosis.summary,
        )
        diagnosis.fixes.forEach { fix -> Timber.tag(TAG).i("対処: %s", fix.display()) }

        writeCache(context, diagnosis)
        return diagnosis
    }

    private fun targetOf(container: Container?): DiagnosisTarget {
        if (container == null) return DiagnosisTarget()
        var adrenotoolsTurnip = true
        runCatching {
            adrenotoolsTurnip = KeyValueSet(container.getGraphicsDriverConfig()).get("adrenotoolsTurnip", "1") != "0"
        }.onFailure { error -> Timber.tag(TAG).w(error, "graphicsDriverConfig を読めませんでした") }
        return DiagnosisTarget(
            containerVariant = container.getContainerVariant().orEmpty(),
            emulator = container.getEmulator().orEmpty(),
            dxwrapper = container.getDXWrapper().orEmpty(),
            graphicsDriver = container.getGraphicsDriver().orEmpty(),
            wineVersion = container.getWineVersion().orEmpty(),
            box64Version = container.getBox64Version().orEmpty(),
            fexcoreVersion = container.getFEXCoreVersion().orEmpty(),
            adrenotoolsTurnip = adrenotoolsTurnip,
        )
    }

    private fun versionsOf(target: DiagnosisTarget): Map<String, String> = buildMap {
        put("variant", target.containerVariant)
        put("wine", target.wineVersion)
        put("emulator", target.emulator)
        put("dxwrapper", target.dxwrapper)
        put("gfx", target.graphicsDriver)
        if (target.box64Version.isNotBlank()) put("box64", target.box64Version)
        if (target.fexcoreVersion.isNotBlank()) put("fexcore", target.fexcoreVersion)
    }

    private fun cacheDir(context: Context): File = File(context.filesDir, "aidiag").apply { mkdirs() }

    private fun readCache(context: Context, hash: String?): Diagnosis? {
        if (hash.isNullOrBlank()) return null
        return runCatching {
            val file = File(cacheDir(context), hash.take(64) + ".json")
            if (!file.isFile || System.currentTimeMillis() - file.lastModified() > CACHE_TTL_MS) return null
            Diagnosis.fromRecordJson(file.readText())
        }.onFailure { error -> Timber.tag(TAG).w(error, "診断キャッシュを読めませんでした") }
            .getOrNull()
    }

    private fun writeCache(context: Context, diagnosis: Diagnosis) {
        runCatching {
            File(cacheDir(context), diagnosis.fingerprintHash.take(64) + ".json")
                .writeText(diagnosis.toRecordJson())
        }.onFailure { error -> Timber.tag(TAG).w(error, "診断キャッシュを書けませんでした") }
    }

    private fun notifyUser(context: Context, diagnosis: Diagnosis) {
        Handler(Looper.getMainLooper()).post {
            runCatching { Toast.makeText(context, diagnosis.displayText(), Toast.LENGTH_LONG).show() }
        }
    }
}
