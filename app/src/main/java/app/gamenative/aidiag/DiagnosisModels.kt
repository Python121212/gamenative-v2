package app.gamenative.aidiag

/** 診断の出所。ルール（端末内・常時有効）か、AI（任意設定）か。 */
enum class DiagnosisSource { RULE, AI }

/**
 * 提案する対処。設定そのものを書き換えるのではなく「提案」として保持する。
 *
 * - [scope] は `container`（コンテナ設定のキー）か `env`（環境変数）。
 * - [key] と [value] は [DiagnosisWhitelist] を通ったものだけが残る。
 *   AI の出力をそのまま設定に流し込まないための要。
 */
data class SuggestedFix(
    val scope: String,
    val key: String,
    val value: String,
    val reason: String,
) {
    /** AI とのやり取りに使う 1 行形式（`scope|key|value|reason`）。 */
    val wire: String
        get() = "$scope|$key|$value|$reason"

    fun display(): String = when (key) {
        "graphicsDriver" -> "グラフィックスドライバを $value にする"
        "dxwrapper" -> "D3D 翻訳を $value にする"
        "emulator" -> "CPU 翻訳を $value にする"
        "containerVariant" -> "実行環境を $value にする"
        "fexcorePreset" -> "FEXCore プリセットを $value にする"
        else -> if (scope == "env") "環境変数 $key=$value" else "$key=$value"
    }
}

/**
 * 1 件の診断結果。フィンガープリント（[fingerprintHash]）でキャッシュし、
 * 同じ失敗に対して何度も AI を呼ばない。
 */
data class Diagnosis(
    val fingerprintHash: String,
    val component: String,
    val kind: String,
    val code: String,
    val summary: String,
    val cause: String,
    val fixes: List<SuggestedFix>,
    val confidence: Float,
    val source: DiagnosisSource,
) {
    fun displayText(): String = buildString {
        append("推定原因: ").append(summary)
        if (cause.isNotBlank()) append("（").append(cause).append("）")
        fixes.take(2).forEach { append('\n').append("・").append(it.display()) }
    }

    /** 通知や UI 用の短い 1 行。 */
    fun shortText(): String = if (fixes.isEmpty()) summary else summary + " → " + fixes.first().display()

    /** キャッシュ用の 1 行 JSON（外部依存なしで読み書きできる最小の形）。 */
    fun toRecordJson(): String = buildString {
        append('{')
        append("\"fingerprint\":").append(jsonQuote(fingerprintHash)).append(',')
        append("\"component\":").append(jsonQuote(component)).append(',')
        append("\"kind\":").append(jsonQuote(kind)).append(',')
        append("\"code\":").append(jsonQuote(code)).append(',')
        append("\"summary\":").append(jsonQuote(summary)).append(',')
        append("\"cause\":").append(jsonQuote(cause)).append(',')
        append("\"confidence\":").append(confidence).append(',')
        append("\"source\":").append(jsonQuote(source.name)).append(',')
        append("\"fixes\":[")
        append(
            fixes.joinToString(",") { fix ->
                "{\"scope\":" + jsonQuote(fix.scope) +
                    ",\"key\":" + jsonQuote(fix.key) +
                    ",\"value\":" + jsonQuote(fix.value) +
                    ",\"reason\":" + jsonQuote(fix.reason) + "}"
            },
        )
        append("]}")
    }

    companion object {
        /**
         * [Diagnosis.toRecordJson] の読み戻し。壊れていれば null（診断は作り直せるので落とさない）。
         * 依存を増やさないため、この形専用の最小パーサとして実装する。
         */
        fun fromRecordJson(json: String): Diagnosis? {
            val fingerprint = field(json, "fingerprint") ?: return null
            val summary = field(json, "summary") ?: return null
            val source = field(json, "source")
                ?.let { runCatching { DiagnosisSource.valueOf(it) }.getOrNull() }
                ?: DiagnosisSource.RULE
            val confidence = field(json, "confidence")?.toFloatOrNull() ?: 0.5f
            val fixes = FIX_PATTERN.findAll(json)
                .map { m ->
                    SuggestedFix(
                        scope = m.groupValues[1],
                        key = jsonUnquote(m.groupValues[2]),
                        value = jsonUnquote(m.groupValues[3]),
                        reason = "",
                    )
                }
                .toList()
            return Diagnosis(
                fingerprintHash = fingerprint,
                component = field(json, "component").orEmpty(),
                kind = field(json, "kind").orEmpty(),
                code = field(json, "code").orEmpty(),
                summary = summary,
                cause = field(json, "cause").orEmpty(),
                fixes = DiagnosisWhitelist.sanitize(fixes),
                confidence = confidence.coerceIn(0f, 1f),
                source = source,
            )
        }

        private val FIX_PATTERN = Regex(
            "\"scope\"\\s*:\\s*\"(container|env)\"\\s*,\\s*\"key\"\\s*:\\s*\"([^\"]+)\"\\s*,\\s*\"value\"\\s*:\\s*\"([^\"]*)\"",
        )

        private fun field(json: String, name: String): String? {
            val match = Regex("\"$name\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"").find(json) ?: return null
            return jsonUnquote(match.groupValues[1])
        }
    }
}

/** 文字列用の最小の JSON エスケープ（制御文字は落とす）。 */
private fun jsonQuote(value: String): String = buildString {
    append('"')
    value.forEach { c ->
        when (c) {
            '"' -> append("\\\"")
            '\\' -> append("\\\\")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            else -> if (c.code < 0x20) append(' ') else append(c)
        }
    }
    append('"')
}

private fun jsonUnquote(value: String): String = value
    .replace("\\n", "\n")
    .replace("\\r", "\r")
    .replace("\\\"", "\"")
    .replace("\\\\", "\\")

/**
 * 診断が触れてよい設定の許可リスト。
 *
 * AI は自由記述を返せるが、**ここに無いキー・値は捨てる**。これにより
 * 「AI が存在しない設定をでっち上げる」「危険な値を書き込む」経路を塞ぐ。
 */
object DiagnosisWhitelist {

    /** コンテナ設定のキー → 許可する値。 */
    private val containerRules: Map<String, (String) -> Boolean> = mapOf(
        "graphicsDriver" to { it in setOf("turnip", "vortek", "virgl", "wrapper", "adreno", "sd-8-elite") },
        "emulator" to { it in setOf("FEXCore", "Box64") },
        "containerVariant" to { it in setOf("bionic", "glibc") },
        "fexcorePreset" to {
            it in setOf("STABILITY", "COMPATIBILITY", "INTERMEDIATE", "PERFORMANCE", "EXTREME", "DENUVO")
        },
        "dxwrapper" to {
            it.matches(Regex("^(dxvk-[0-9][A-Za-z0-9._-]*|vkd3d-[0-9][A-Za-z0-9._-]*|wined3d|cnc-ddraw)$"))
        },
    )

    /** 環境変数のキー → 値のバリデータ。 */
    private val envRules: Map<String, (String) -> Boolean> = buildMap {
        val flag: (String) -> Boolean = { it == "0" || it == "1" }
        put("DXVK_ASYNC", flag)
        put("DXVK_GPLASYNCCACHE", flag)
        put("BOX64_DYNAREC", flag)
        put("FEX_TSOENABLED", flag)
        put("FEX_X87REDUCEDPRECISION", flag)
        put("WRAPPER_NO_PATCH_OPCONSTCOMP", flag)
        put("vblank_mode", flag)
        put("DXVK_FRAME_RATE", { value -> (value.toIntOrNull() ?: 0) in 1..240 })
        put("DXVK_HUD", { it.length <= 64 && it.matches(Regex("[A-Za-z0-9,=_ .-]*")) })
        put("TU_DEBUG", { it.length <= 64 && it.matches(Regex("[a-z0-9,_=-]*")) })
        put("MESA_VK_WSI_DEBUG", { it.length <= 32 && it.matches(Regex("[a-z,_-]*")) })
        put("VKD3D_DEBUG", { it.length <= 32 && it.matches(Regex("[a-z,]*")) })
    }

    /** AI のプロンプトに載せる「触れてよいキー」の一覧。 */
    val allowedSummary: String
        get() = "container: " + containerRules.keys.joinToString(", ") +
            " / env: " + envRules.keys.joinToString(", ")

    /** 1 件を検査する。通らなければ null（捨てる）。 */
    fun sanitize(fix: SuggestedFix): SuggestedFix? {
        val key = fix.key.trim()
        val value = fix.value.trim()
        if (key.isEmpty() || value.length > 120) return null
        if (value.any { it.isISOControl() }) return null
        val valid = when (fix.scope) {
            "container" -> containerRules[key]?.invoke(value) == true
            "env" -> envRules[key]?.invoke(value) == true
            else -> false
        }
        if (!valid) return null
        return fix.copy(key = key, value = value, reason = fix.reason.trim().take(160))
    }

    fun sanitize(fixes: List<SuggestedFix>): List<SuggestedFix> = fixes.asSequence()
        .mapNotNull { sanitize(it) }
        .distinctBy { it.scope + "|" + it.key }
        .take(4)
        .toList()
}
