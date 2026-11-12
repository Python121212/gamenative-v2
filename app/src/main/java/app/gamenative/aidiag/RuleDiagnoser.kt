package app.gamenative.aidiag

import app.gamenative.utils.CrashFingerprint

/**
 * 診断の対象となる実行環境（コンテナ設定の写し）。Android に依存しないので単体テストできる。
 */
data class DiagnosisTarget(
    val containerVariant: String = "",
    val emulator: String = "",
    val dxwrapper: String = "",
    val graphicsDriver: String = "",
    val wineVersion: String = "",
    val box64Version: String = "",
    val fexcoreVersion: String = "",
    val adrenotoolsTurnip: Boolean = true,
)

/**
 * ルールベースの診断。端末内で完結し、ネットワークも AI も要らない。
 *
 * [CrashFingerprint] が作る「どのコンポーネントが、どんな種類の失敗をしたか」と、
 * ログのダイジェストから既知のパターンを判定し、**許可リスト内の対処**を返す。
 */
object RuleDiagnoser {

    /** これ以上の確度なら AI に問い合わせない（コストと遅延を抑える）。 */
    const val HIGH_CONFIDENCE = 0.7f

    fun diagnose(
        fingerprint: CrashFingerprint.Fingerprint?,
        digest: String,
        target: DiagnosisTarget,
    ): Diagnosis? {
        // 材料が無ければ何も言えない（推測で設定を触らせない）。
        if (fingerprint == null && digest.isBlank()) return null
        val text = digest.lowercase()
        val component = fingerprint?.component.orEmpty()
        val kind = fingerprint?.kind.orEmpty()
        val code = fingerprint?.code.orEmpty()
        val hash = fingerprint?.hash ?: "digest:${digest.take(64).hashCode()}"

        knownRules.firstNotNullOfOrNull { rule ->
            rule(Evidence(component, kind, code, text, target))
        }?.let { return it.build(hash) }
        return null
    }

    /** 1 つのルールが参照する材料。 */
    private class Evidence(
        val component: String,
        val kind: String,
        val code: String,
        val text: String,
        val target: DiagnosisTarget,
    ) {
        fun logHas(vararg needles: String): Boolean = needles.any { it in text }
    }

    /** ルールの出力（フィンガープリントは呼び出し側で付ける）。 */
    private class Finding(
        val component: String,
        val kind: String,
        val code: String,
        val summary: String,
        val cause: String,
        val confidence: Float,
        val fixes: List<SuggestedFix>,
    ) {
        fun build(hash: String): Diagnosis = Diagnosis(
            fingerprintHash = hash,
            component = component,
            kind = kind,
            code = code,
            summary = summary,
            cause = cause,
            fixes = DiagnosisWhitelist.sanitize(fixes),
            confidence = confidence.coerceIn(0f, 1f),
            source = DiagnosisSource.RULE,
        )
    }

    private fun fix(key: String, value: String, reason: String) = SuggestedFix("container", key, value, reason)
    private fun env(key: String, value: String, reason: String) = SuggestedFix("env", key, value, reason)

    private val knownRules: List<(Evidence) -> Finding?> = listOf(
        // 1. GPU ドライバのハング / デバイスロスト。
        { e ->
            val deviceLost = e.kind == "device_lost" ||
                e.logHas("vk_error_device_lost", "device lost", "gpu hang", "hang detected")
            if (!deviceLost) {
                null
            } else {
                Finding(
                    component = e.component.ifEmpty { "mesa" },
                    kind = "device_lost",
                    code = e.code,
                    summary = "GPU ドライバがハングし、Vulkan デバイスが失われた",
                    cause = "ドライバか Vulkan 翻訳層の不整合。Adrenotools/Turnip の組み合わせで起きやすい",
                    confidence = 0.75f,
                    fixes = buildList {
                        if (e.target.graphicsDriver != "turnip") {
                            add(fix("graphicsDriver", "turnip", "Turnip に変えるとデバイスロストが減る"))
                        }
                        add(env("WRAPPER_NO_PATCH_OPCONSTCOMP", "1", "SPIR-V パッチを無効化してドライバ側の不具合を避ける"))
                    },
                )
            }
        },

        // 2. VRAM 不足。
        { e ->
            val oom = e.logHas(
                "vk_error_out_of_device_memory",
                "out of device memory",
                "out of memory",
                "failed to allocate memory",
            )
            if (!oom) {
                null
            } else {
                Finding(
                    component = e.component.ifEmpty { "mesa" },
                    kind = "out_of_memory",
                    code = e.code,
                    summary = "GPU/CPU のメモリが不足して確保に失敗した",
                    cause = "テクスチャやシェーダーが端末のメモリ上限を超えている",
                    confidence = 0.7f,
                    fixes = listOf(
                        env("DXVK_FRAME_RATE", "30", "フレームレートを下げて確保量を減らす"),
                        fix("graphicsDriver", "turnip", "メモリ管理が安定するドライバに変える"),
                    ),
                )
            }
        },

        // 3. FEXCore が未実装の命令・システムコールに当たった。
        { e ->
            val fex = e.component == "fex" ||
                e.logHas("fex: unhandled", "fexcore", "unimplemented instruction")
            val unimplemented = e.kind == "unimplemented" || e.kind == "exception" || e.logHas("unimplemented")
            if (!fex || !unimplemented || e.target.emulator.equals("Box64", ignoreCase = true)) {
                null
            } else {
                Finding(
                    component = "fex",
                    kind = e.kind.ifEmpty { "unimplemented" },
                    code = e.code,
                    summary = "FEXCore がゲームの命令（またはシステムコール）を解釈できなかった",
                    cause = "FEXCore の未対応命令。Box64 では別実装のため通ることがある",
                    confidence = 0.7f,
                    fixes = listOf(
                        fix("emulator", "Box64", "CPU 翻訳を Box64 に切り替える"),
                        fix("fexcorePreset", "COMPATIBILITY", "FEXCore の互換性重視プリセットを試す"),
                    ),
                )
            }
        },

        // 4. Box64 が未実装のオペコード・システムコールに当たった。
        { e ->
            val box = e.component == "box64" || e.logHas("box64", "dynarec", "unsupported syscall")
            val unimplemented = e.kind == "unimplemented" || e.logHas("unimplemented opcode", "unsupported syscall")
            if (!box || !unimplemented || e.target.emulator.equals("FEXCore", ignoreCase = true)) {
                null
            } else {
                Finding(
                    component = "box64",
                    kind = e.kind.ifEmpty { "unimplemented" },
                    code = e.code,
                    summary = "Box64 がゲームのオペコード（またはシステムコール）を解釈できなかった",
                    cause = "Box64 の未対応命令。FEXCore に切り替えると通ることがある",
                    confidence = 0.65f,
                    fixes = listOf(
                        fix("emulator", "FEXCore", "CPU 翻訳を FEXCore に切り替える"),
                        env("BOX64_DYNAREC", "0", "Dynarec を切って命令の取りこぼしを避ける"),
                    ),
                )
            }
        },

        // 5. DXVK/VKD3D の読み込み失敗（DLL が見つからない）。
        { e ->
            val loadFailure = e.kind == "load_failure" || e.logHas("err:module:import_dll", "library not found")
            val d3d = e.logHas("d3d11", "d3d9", "d3d12", "dxgi", "d3d10") ||
                e.code.lowercase().let { it.startsWith("d3d") || it.startsWith("dxgi") }
            if (!loadFailure || !d3d) {
                null
            } else {
                Finding(
                    component = "wine",
                    kind = "load_failure",
                    code = e.code,
                    summary = "Direct3D の DLL（DXVK/VKD3D）を読み込めなかった",
                    cause = "展開が壊れているか、選択中のコンポーネントとゲームの要求が噛み合っていない",
                    confidence = 0.68f,
                    fixes = listOf(
                        fix("dxwrapper", "wined3d", "いったん Wine 内蔵の wined3d に戻して切り分ける"),
                    ),
                )
            }
        },

        // 6. Wine が未実装関数を呼ばれた（d3d 系）。
        { e ->
            val wine = e.component == "wine"
            val unimplemented = e.kind == "unimplemented"
            val d3d = e.code.lowercase().startsWith("d3d") || e.code.lowercase().startsWith("dxgi") ||
                e.logHas("unimplemented function d3d", "unimplemented function dxgi")
            if (!wine || !unimplemented || !d3d) {
                null
            } else {
                Finding(
                    component = "wine",
                    kind = "unimplemented",
                    code = e.code,
                    summary = "Wine 側に未実装の Direct3D 関数があり、ゲームが停止した",
                    cause = "wined3d のカバー範囲外。DXVK/VKD3D に切り替えると代替実装で動く",
                    confidence = 0.62f,
                    fixes = listOf(
                        env("DXVK_ASYNC", "0", "非同期コンパイルを切って初期化を通しやすくする"),
                    ),
                )
            }
        },

        // 7. シェーダーのコンパイル失敗（vkd3d-shader / DXVK）。
        { e ->
            val shader = e.logHas("shader compilation failed", "e5017", "failed to compile shader", "dxil")
            if (!shader) {
                null
            } else {
                Finding(
                    component = e.component.ifEmpty { "dxvk" },
                    kind = "shader_compile",
                    code = e.code,
                    summary = "シェーダーのコンパイルに失敗した",
                    cause = "翻訳層（vkd3d-shader / DXVK）が未対応のシェーダー構文を含む",
                    confidence = 0.6f,
                    fixes = listOf(
                        fix("dxwrapper", "wined3d", "別の翻訳経路（wined3d）で動くか確認する"),
                        env("DXVK_ASYNC", "0", "非同期コンパイルを切る"),
                    ),
                )
            }
        },

        // 8. Vulkan の拡張・ドライバが不足している。
        { e ->
            val vkMissing = e.logHas(
                "vk_error_extension_not_present",
                "vk_error_incompatible_driver",
                "vulkan driver not found",
            )
            if (!vkMissing) {
                null
            } else {
                Finding(
                    component = "mesa",
                    kind = "vulkan_unavailable",
                    code = e.code,
                    summary = "Vulkan の拡張またはドライバが見つからず初期化に失敗した",
                    cause = "選択中の ICD が端末の Vulkan バージョンに合っていない",
                    confidence = 0.7f,
                    fixes = listOf(
                        fix("graphicsDriver", "turnip", "Turnip の ICD に切り替える"),
                    ),
                )
            }
        },

        // 9. ゲームが正常終了以外で終わったが、目立った手がかりが無い。
        { e ->
            val noSignal = e.component == "none" || (e.component.isEmpty() && e.text.isBlank())
            if (!noSignal) {
                null
            } else {
                Finding(
                    component = "none",
                    kind = "other",
                    code = e.code,
                    summary = "ゲームが理由を残さず終了した",
                    cause = "ログに手がかりが無いため、切り分けには設定の変更や追加ログが必要",
                    confidence = 0.25f,
                    fixes = emptyList(),
                )
            }
        },
    )
}
