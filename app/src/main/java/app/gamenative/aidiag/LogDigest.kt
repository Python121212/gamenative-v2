package app.gamenative.aidiag

/**
 * ログを「診断に必要な形」に削る。
 *
 * - 個人情報・資格情報を伏せる（パス、ユーザー名、メール、トークン、IP、SteamID）。
 * - ノイズ（fixme/trace、空行の連続）を落とす。
 * - 同じ行の繰り返しを 1 行に畳む（`×N`）。
 *
 * 端末内の判定にも、外部 AI に送る場合にも必ずここを通す。純粋関数なので単体テストできる。
 */
object LogDigest {

    /** アプリのデータ領域・外部ストレージのパス。ユーザー名やゲーム名を含むので伏せる。 */
    private val ABSOLUTE_PATH = Regex("""/(?:data/(?:user/\d+|data)|storage/(?:emulated/\d+|[A-Za-z0-9_-]+))[^\s"']*""")

    /** `/home/<user>/...` 形式。 */
    private val HOME_PATH = Regex("""/home/[^\s"']+""")

    /** ドライブレター付きの Windows パス（`C:\Users\<name>\...`）はユーザー名だけ伏せる。 */
    private val WINDOWS_USER = Regex("""(?i)([A-Za-z]:\\Users\\)[^\\\s"']+""")

    private val EMAIL = Regex("""[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}""")

    /** `Authorization: Bearer …`、`api_key=…` などの資格情報。 */
    private val SECRET = Regex(
        """(?i)\b(bearer|api[_-]?key|access[_-]?token|refresh[_-]?token|client[_-]?secret|password|passwd|token)\b\s*[:=]?\s*([A-Za-z0-9._~+/=-]{8,})""",
    )

    /** 32 桁以上の 16 進はトークン・ハッシュの類とみなす（ただし DLL 名やエラーコードは短いので残る）。 */
    private val LONG_HEX = Regex("""\b[0-9a-fA-F]{32,}\b""")

    private val IPV4 = Regex("""\b\d{1,3}(?:\.\d{1,3}){3}\b""")

    private val STEAM_ID = Regex("""\b7656\d{13}\b""")

    /** 診断に寄与しないノイズ行（fixme/trace、wine の定型文、空行）。 */
    private val NOISE = Regex("""^(?:fixme|trace):|^wine: (?:created|read) new|^\s*$""", RegexOption.IGNORE_CASE)

    private val LINE_PREFIX = Regex("""^(?:\d{2}:\d{2}:\d{2}\.\d+\s+\d+\s+\d+\s+[A-Z]\s+\S+\s*:?\s*)""")

    const val DEFAULT_MAX_LINES = 200
    const val DEFAULT_MAX_CHARS = 8000

    /** 1 行を伏字化する（長すぎる行は切り詰める）。 */
    fun redact(line: String): String {
        var s = line.trimEnd()
        if (s.length > 400) s = s.take(400) + "…"
        s = SECRET.replace(s) { m -> "${m.groupValues[1]}=<redacted>" }
        s = ABSOLUTE_PATH.replace(s, "/<path>")
        s = HOME_PATH.replace(s, "/home/<user>")
        s = WINDOWS_USER.replace(s, "$1<user>")
        s = EMAIL.replace(s, "<email>")
        s = LONG_HEX.replace(s, "<hex>")
        s = STEAM_ID.replace(s, "<steamid>")
        s = IPV4.replace(s, "<ip>")
        return s
    }

    /**
     * 伏字化・ノイズ除去・重複の畳み込みを行ったダイジェストを作る。
     * [maxLines] を超える場合は**末尾**を残す（クラッシュは最後に出る）。
     */
    fun digest(raw: String, maxLines: Int = DEFAULT_MAX_LINES, maxChars: Int = DEFAULT_MAX_CHARS): String {
        if (raw.isBlank()) return ""
        val collapsed = ArrayList<String>()
        var previous: String? = null
        var repeat = 0

        fun flushRepeat() {
            if (repeat > 0 && previous != null) {
                collapsed[collapsed.size - 1] = "$previous  (×${repeat + 1})"
            }
            repeat = 0
        }

        for (rawLine in raw.lineSequence()) {
            val line = redact(LINE_PREFIX.replace(rawLine, ""))
            // containsMatchIn: NOISE は行頭一致のパターンなので、全文一致では拾えない。
            if (NOISE.containsMatchIn(line)) continue
            if (line == previous) {
                repeat++
                continue
            }
            flushRepeat()
            collapsed.add(line)
            previous = line
        }
        flushRepeat()

        val tail = if (collapsed.size > maxLines) collapsed.subList(collapsed.size - maxLines, collapsed.size) else collapsed
        val sb = StringBuilder()
        for (line in tail) {
            if (sb.length + line.length + 1 > maxChars) break
            sb.append(line).append('\n')
        }
        return sb.toString().trimEnd()
    }
}
