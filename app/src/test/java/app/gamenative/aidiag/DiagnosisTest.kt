package app.gamenative.aidiag

import app.gamenative.utils.CrashFingerprint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** ログの伏字化・圧縮。 */
class LogDigestTest {

    @Test
    fun `パスとユーザー名を伏せる`() {
        val digest = LogDigest.digest(
            "/data/user/0/app.gamenative/files/imagefs/home/xuser/.wine/drive_c/Game/game.exe: not found",
        )

        assertFalse(digest.contains("app.gamenative"))
        assertFalse(digest.contains("xuser"))
        assertTrue(digest.contains("<path>"))
        assertTrue(digest.contains("not found"))
    }

    @Test
    fun `資格情報とメールと SteamID を伏せる`() {
        val digest = LogDigest.digest(
            """
            Authorization: Bearer abcdef1234567890secret
            api_key=deadbeefdeadbeefdeadbeef
            user=player@example.com steam=76561198000000000
            """.trimIndent(),
        )

        assertFalse(digest.contains("abcdef1234567890secret"))
        assertFalse(digest.contains("deadbeefdeadbeefdeadbeef"))
        assertFalse(digest.contains("player@example.com"))
        assertFalse(digest.contains("76561198000000000"))
        assertTrue(digest.contains("<email>"))
        assertTrue(digest.contains("<steamid>"))
    }

    @Test
    fun `技術的な手がかりは残す`() {
        val digest = LogDigest.digest(
            "err:vulkan:VK_ERROR_DEVICE_LOST from d3d11.dll while creating resource 0x1a2b3c",
        )

        assertTrue(digest.contains("VK_ERROR_DEVICE_LOST"))
        assertTrue(digest.contains("d3d11.dll"))
    }

    @Test
    fun `同じ行の繰り返しを畳む`() {
        val digest = LogDigest.digest(List(20) { "wine: Unhandled page fault on read access" }.joinToString("\n"))

        assertEquals(1, digest.lines().size)
        assertTrue(digest.contains("×20"))
    }

    @Test
    fun `fixme と空行は落とす`() {
        val digest = LogDigest.digest(
            """
            fixme:ntdll:NtQueryInformationProcess
            err:module:import_dll Library d3d11.dll not found

            trace:sync:something
            """.trimIndent(),
        )

        assertFalse(digest.contains("fixme"))
        assertFalse(digest.contains("trace"))
        assertTrue(digest.contains("import_dll"))
    }

    @Test
    fun `長すぎるログは末尾を残す`() {
        val raw = (1..500).joinToString("\n") { "line $it" }
        val digest = LogDigest.digest(raw, maxLines = 20)

        assertEquals(20, digest.lines().size)
        assertTrue(digest.contains("line 500"))
        assertFalse(digest.contains("line 1\n"))
    }
}

/** 対処の許可リスト（AI の出力をそのまま設定に流さないための要）。 */
class DiagnosisWhitelistTest {

    @Test
    fun `既知のコンテナ設定は通す`() {
        val fixes = DiagnosisWhitelist.sanitize(
            listOf(
                SuggestedFix("container", "graphicsDriver", "turnip", "ドライバを変える"),
                SuggestedFix("container", "emulator", "Box64", "エミュレータを変える"),
                SuggestedFix("container", "dxwrapper", "dxvk-2.7.1", "D3D 翻訳を変える"),
                SuggestedFix("env", "DXVK_ASYNC", "0", "非同期を切る"),
            ),
        )

        assertEquals(4, fixes.size)
    }

    @Test
    fun `未知のキーや不正な値は捨てる`() {
        val fixes = DiagnosisWhitelist.sanitize(
            listOf(
                SuggestedFix("container", "rm -rf", "/system", "悪意ある例"),
                SuggestedFix("container", "graphicsDriver", "hacked", "存在しないドライバ"),
                SuggestedFix("env", "LD_PRELOAD", "/system/lib evil.so", "任意コード実行"),
                SuggestedFix("env", "DXVK_FRAME_RATE", "100000", "範囲外"),
                SuggestedFix("env", "DXVK_HUD", "fps", "正しい例"),
            ),
        )

        assertEquals(1, fixes.size)
        assertEquals("DXVK_HUD", fixes.first().key)
    }

    @Test
    fun `改行を含む値は捨てる`() {
        assertNull(DiagnosisWhitelist.sanitize(SuggestedFix("env", "TU_DEBUG", "sysmem\nrm -rf /", "改行")))
    }

    @Test
    fun `同じキーの重複は 1 つに畳む`() {
        val fixes = DiagnosisWhitelist.sanitize(
            listOf(
                SuggestedFix("env", "DXVK_ASYNC", "0", "1 件目"),
                SuggestedFix("env", "DXVK_ASYNC", "1", "2 件目"),
            ),
        )

        assertEquals(1, fixes.size)
        assertEquals("0", fixes.first().value)
    }
}

/** 端末内のルール判定。 */
class RuleDiagnoserTest {

    private fun fingerprint(component: String, kind: String, code: String = "") =
        CrashFingerprint.Fingerprint(
            component = component,
            kind = kind,
            code = code,
            module = "",
            frames = emptyList(),
            sample = listOf("sample line"),
            hash = "hash-$component-$kind",
            versions = emptyMap(),
        )

    private val target = DiagnosisTarget(
        containerVariant = "bionic",
        emulator = "FEXCore",
        dxwrapper = "dxvk-2.7.1",
        graphicsDriver = "wrapper",
    )

    @Test
    fun `デバイスロストはドライバ変更を提案する`() {
        val diagnosis = RuleDiagnoser.diagnose(
            fingerprint("dxvk", "device_lost", "VK_ERROR_DEVICE_LOST"),
            digest = "err:vulkan:VK_ERROR_DEVICE_LOST",
            target = target,
        )

        assertNotNull(diagnosis)
        assertEquals(DiagnosisSource.RULE, diagnosis!!.source)
        assertTrue(diagnosis.confidence >= RuleDiagnoser.HIGH_CONFIDENCE)
        assertTrue(diagnosis.fixes.any { it.key == "graphicsDriver" && it.value == "turnip" })
    }

    @Test
    fun `FEXCore の未実装命令は Box64 を提案する`() {
        val diagnosis = RuleDiagnoser.diagnose(
            fingerprint("fex", "unimplemented", "0F 0B"),
            digest = "FEX: Unhandled instruction bytes 0F 0B",
            target = target,
        )

        assertNotNull(diagnosis)
        assertTrue(diagnosis!!.fixes.any { it.key == "emulator" && it.value == "Box64" })
    }

    @Test
    fun `すでに Box64 なら FEXCore への切り替えを提案する`() {
        val diagnosis = RuleDiagnoser.diagnose(
            fingerprint("box64", "unimplemented", "unsupported syscall"),
            digest = "box64: Unsupported Syscall 0x1234h (4660)",
            target = target.copy(emulator = "Box64"),
        )

        assertNotNull(diagnosis)
        assertTrue(diagnosis!!.fixes.any { it.key == "emulator" && it.value == "FEXCore" })
    }

    @Test
    fun `Direct3D の DLL 読み込み失敗は wined3d への切り戻しを提案する`() {
        val diagnosis = RuleDiagnoser.diagnose(
            fingerprint("wine", "load_failure", "d3d11"),
            digest = "err:module:import_dll Library d3d11.dll not found",
            target = target,
        )

        assertNotNull(diagnosis)
        assertTrue(diagnosis!!.fixes.any { it.key == "dxwrapper" && it.value == "wined3d" })
    }

    @Test
    fun `VRAM 不足はフレームレート制限を提案する`() {
        val diagnosis = RuleDiagnoser.diagnose(
            fingerprint("mesa", "other", "VK_ERROR_OUT_OF_DEVICE_MEMORY"),
            digest = "VK_ERROR_OUT_OF_DEVICE_MEMORY",
            target = target,
        )

        assertNotNull(diagnosis)
        assertTrue(diagnosis!!.fixes.any { it.key == "DXVK_FRAME_RATE" })
    }

    @Test
    fun `手がかりが無い異常終了は低い確度で返し AI に回せる`() {
        val diagnosis = RuleDiagnoser.diagnose(fingerprint("none", "other", "guest_terminated"), "", target)

        assertNotNull(diagnosis)
        assertTrue(diagnosis!!.confidence < RuleDiagnoser.HIGH_CONFIDENCE)
        assertTrue(diagnosis.fixes.isEmpty())
    }

    @Test
    fun `材料が無ければ何も返さない`() {
        assertNull(RuleDiagnoser.diagnose(null, "", target))
    }
}

/** AI 応答のパース。 */
class AiDiagnosisParserTest {

    @Test
    fun `定型の応答を読み取る`() {
        val diagnosis = AiDiagnosisParser.parse(
            """
            SUMMARY: GPU ドライバがハングした
            CAUSE: Adrenotools のドライバがゲームのシェーダーを処理できなかった
            FIX: container|graphicsDriver|turnip|Turnip のほうが安定する
            FIX: env|DXVK_ASYNC|0|非同期コンパイルを切る
            CONFIDENCE: 0.8
            """.trimIndent(),
            fingerprintHash = "hash",
            component = "dxvk",
            kind = "device_lost",
            code = "VK_ERROR_DEVICE_LOST",
        )

        assertNotNull(diagnosis)
        assertEquals(DiagnosisSource.AI, diagnosis!!.source)
        assertEquals("GPU ドライバがハングした", diagnosis.summary)
        assertEquals(0.8f, diagnosis.confidence, 0.001f)
        assertEquals(2, diagnosis.fixes.size)
    }

    @Test
    fun `許可外の対処は捨てる`() {
        val diagnosis = AiDiagnosisParser.parse(
            """
            SUMMARY: 何かが起きた
            FIX: env|LD_PRELOAD|/system/lib/evil.so|任意コード
            FIX: container|graphicsDriver|turnip|正しい対処
            """.trimIndent(),
            fingerprintHash = "hash",
            component = "",
            kind = "",
            code = "",
        )

        assertNotNull(diagnosis)
        assertEquals(1, diagnosis!!.fixes.size)
        assertEquals("graphicsDriver", diagnosis.fixes.first().key)
    }

    @Test
    fun `SUMMARY が無い応答は無効`() {
        assertNull(
            AiDiagnosisParser.parse(
                "FIX: container|graphicsDriver|turnip|理由",
                fingerprintHash = "hash",
                component = "",
                kind = "",
                code = "",
            ),
        )
    }

    @Test
    fun `確度は 0 から 1 に丸める`() {
        val diagnosis = AiDiagnosisParser.parse(
            "SUMMARY: テスト\nCONFIDENCE: 7.5",
            fingerprintHash = "hash",
            component = "",
            kind = "",
            code = "",
        )

        assertEquals(1f, diagnosis!!.confidence, 0.001f)
    }
}

/** 診断結果の保存と読み戻し。 */
class DiagnosisCacheTest {

    @Test
    fun `保存した診断を読み戻せる`() {
        val diagnosis = Diagnosis(
            fingerprintHash = "abc123",
            component = "dxvk",
            kind = "device_lost",
            code = "VK_ERROR_DEVICE_LOST",
            summary = "GPU ドライバがハングした",
            cause = "ドライバの不整合",
            fixes = listOf(SuggestedFix("container", "graphicsDriver", "turnip", "安定する")),
            confidence = 0.75f,
            source = DiagnosisSource.AI,
        )

        val restored = Diagnosis.fromRecordJson(diagnosis.toRecordJson())

        assertNotNull(restored)
        assertEquals(diagnosis.summary, restored!!.summary)
        assertEquals(DiagnosisSource.AI, restored.source)
        assertEquals(1, restored.fixes.size)
        assertEquals("turnip", restored.fixes.first().value)
    }

    @Test
    fun `壊れた記録は null`() {
        assertNull(Diagnosis.fromRecordJson("not json"))
    }
}
