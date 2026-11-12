package app.gamenative.aidiag

/**
 * AI の応答を [Diagnosis] に変換する。
 *
 * 応答は JSON ではなく **行指向の固定書式**を要求する（パースが決定的で、壊れた応答を捨てやすい）。
 *
 * ```
 * SUMMARY: 何が起きたか（1 文）
 * CAUSE: 推定原因（1 文）
 * FIX: container|graphicsDriver|turnip|理由
 * FIX: env|DXVK_ASYNC|0|理由
 * CONFIDENCE: 0.7
 * ```
 *
 * 許可リスト（[DiagnosisWhitelist]）に無い FIX は捨てる。SUMMARY が無い応答は無効。
 */
object AiDiagnosisParser {

    fun parse(
        text: String,
        fingerprintHash: String,
        component: String,
        kind: String,
        code: String,
    ): Diagnosis? {
        if (text.isBlank()) return null
        var summary: String? = null
        var cause = ""
        var confidence = 0.5f
        val fixes = ArrayList<SuggestedFix>()

        for (rawLine in text.lineSequence()) {
            val line = rawLine.trim().removePrefix("-").trim()
            if (line.isEmpty()) continue
            val colon = line.indexOf(':')
            if (colon <= 0) continue
            val label = line.substring(0, colon).trim().uppercase()
            val body = line.substring(colon + 1).trim()
            when (label) {
                "SUMMARY" -> if (summary == null && body.isNotEmpty()) summary = body.take(240)
                "CAUSE" -> if (cause.isEmpty() && body.isNotEmpty()) cause = body.take(240)
                "CONFIDENCE" -> confidence = body.toFloatOrNull() ?: confidence
                "FIX" -> {
                    val parts = body.split('|').map { it.trim() }
                    if (parts.size >= 3) {
                        val scope = parts[0].lowercase()
                        val key = parts[1]
                        val value = parts[2]
                        val reason = parts.getOrElse(3) { "" }
                        fixes += SuggestedFix(scope, key, value, reason)
                    }
                }
            }
        }

        val cleanSummary = summary ?: return null
        return Diagnosis(
            fingerprintHash = fingerprintHash,
            component = component,
            kind = kind,
            code = code,
            summary = cleanSummary,
            cause = cause,
            fixes = DiagnosisWhitelist.sanitize(fixes),
            confidence = confidence.coerceIn(0f, 1f),
            source = DiagnosisSource.AI,
        )
    }
}
