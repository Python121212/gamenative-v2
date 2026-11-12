package app.gamenative.translation

/**
 * ゲームの実行パイプライン。Windows のゲームを Android 端末で動かすには、次の 4 段で「翻訳」が起きる。
 *
 * ```
 * x86-64 実行ファイル → [CPU] → AArch64 ネイティブ
 * Win32 API          → [WINDOWS] → Linux (Android)
 * Direct3D 8〜12     → [DIRECT3D] → Vulkan または OpenGL
 * グラフィックス API  → [GRAPHICS_API] → Vulkan または GL
 * Vulkan             → [GPU_DRIVER] → GPU ドライバ（ICD は 1 つだけ）
 * ```
 *
 * 各段で有効なレイヤーは **ちょうど 1 つ**でなければならない（[TranslationStackPlan.isSingleLayerPerStage]）。
 * 2 つ以上が同時に有効になると、余分なコピー・余分なホップ・設定の食い違いが生まれる。
 * このパッケージはその「多重翻訳」を検出し、死んでいる層の環境変数を取り除く。
 */
enum class TranslationStage(val label: String, val japaneseName: String) {
    /** x86-64 → AArch64 の命令翻訳（FEXCore / Box64）。 */
    CPU("cpu", "CPU 翻訳"),

    /** Win32 API → Linux の実装差し替え（Wine / Proton）。 */
    WINDOWS("win", "Windows ランタイム"),

    /** Direct3D → Vulkan/OpenGL の描画翻訳（DXVK / VKD3D / wined3d / cnc-ddraw）。 */
    DIRECT3D("d3d", "Direct3D 翻訳"),

    /** グラフィックス API → Vulkan ローダに渡す実装（Vulkan か GL か）。 */
    GRAPHICS_API("gfx", "グラフィックス API"),

    /**
     * Vulkan → GPU ドライバ。ローダが読み込む ICD（Installable Client Driver）は **1 つだけ**でなければならない。
     * Turnip（freedreno ICD）／Adrenotools（wrapper ICD）／Vortek（in-process）／Virgl（Vulkan なし）のどれか 1 つ。
     */
    GPU_DRIVER("icd", "GPU ドライバ (ICD)"),
}

/** ある段で実際に有効な翻訳レイヤー。 */
data class TranslationLayer(
    val stage: TranslationStage,
    val id: String,
    val version: String = "",
) {
    val label: String
        get() = if (version.isBlank() || version.equals(id, ignoreCase = true)) id else "$id-$version"

    override fun toString(): String = "${stage.label}=$label"
}

/** 検出した多重化に対して、実際に何をしたか（あるいは何もできなかったか）。 */
enum class ConflictAction {
    /** 死んでいた層の環境変数を取り除いた（実行時の挙動に影響しない安全な除去）。 */
    ENV_STRIPPED,

    /** 除去すると挙動が変わるため、記録だけして残した（画面上・ログで可視化）。 */
    REPORTED,

    /** 設定の不足を補って 1 本の計画に揃えた。 */
    FIXED,
}

/** 検出した多重翻訳・不整合。 */
data class TranslationConflict(
    val stage: TranslationStage,
    val kind: String,
    val detail: String,
    val action: ConflictAction,
)

/**
 * 1 回の起動に対する「翻訳スタック計画」。
 *
 * - [layers]: 段ごとに有効なレイヤー（[TranslationStage.DIRECT3D] のみ、API の担当が分かれている場合に 2 つ
 *   以上を持つことがある。例: D3D12 は vkd3d、D3D9〜11 は DXVK）。
 * - [deadEnvKeys]: 有効でない層に属しているため、実行時に読まれない環境変数。適用時に削除する。
 * - [conflicts]: 記録した多重化・不整合。
 */
data class TranslationStackPlan(
    val layers: List<TranslationLayer>,
    val deadEnvKeys: List<String>,
    val conflicts: List<TranslationConflict>,
    /**
     * この起動で Vulkan ローダに渡すべき ICD マニフェストのファイル名（例: `wrapper_icd.aarch64.json`）。
     * `null` は「Vulkan を経由しない（Virgl の GL 直結）」または「セッション固有の ICD をそのまま使う」。
     */
    val expectedIcdManifest: String? = null,
) {
    fun layer(stage: TranslationStage): TranslationLayer? = layers.firstOrNull { it.stage == stage }

    fun layersOf(stage: TranslationStage): List<TranslationLayer> = layers.filter { it.stage == stage }

    /**
     * CPU・Windows・グラフィックス API の段は 1 レイヤーまで。Direct3D だけは API ごとの担当分けを許す
     * （DXVK と VKD3D の併用は、それぞれ別の API を受け持つので 1 本の計画として扱える）。
     */
    val isSingleLayerPerStage: Boolean
        get() = TranslationStage.entries.all { stage ->
            val count = layersOf(stage).size
            if (stage == TranslationStage.DIRECT3D) count <= 2 else count <= 1
        }

    /** 翻訳のホップ数（段の数）。この数がそのまま「翻訳が何段重なっているか」。 */
    val hopCount: Int
        get() = TranslationStage.entries.count { layersOf(it).isNotEmpty() }

    /** 実際に通るパイプラインの 1 行表現。D3D11/12 → Vulkan → ドライバ が直結しているかが読み取れる。 */
    fun chainedDescription(): String {
        val cpu = layer(TranslationStage.CPU)?.label ?: "native-arm64"
        val win = layer(TranslationStage.WINDOWS)?.label ?: "none"
        val d3d = layersOf(TranslationStage.DIRECT3D).joinToString("/") { it.label }.ifEmpty { "none" }
        val gfx = layer(TranslationStage.GRAPHICS_API)?.label ?: "none"
        val icd = layer(TranslationStage.GPU_DRIVER)?.label ?: "none"
        return "x86-64 → $cpu → aarch64 ; win32 → $win ; d3d → $d3d → $gfx → $icd"
    }

    /** ログ・デバッグ画面用の 1 行サマリ。 */
    fun summary(): String = buildString {
        append("translation-stack ")
        TranslationStage.entries.forEach { stage ->
            val labels = layersOf(stage).joinToString("+") { it.label }
            append(stage.label).append('=').append(labels.ifEmpty { "none" }).append(' ')
        }
        append("hops=").append(hopCount)
        append(" dedup=").append(deadEnvKeys.size)
        append(" conflicts=").append(conflicts.size)
        append(" single=").append(isSingleLayerPerStage)
    }

    /** 計画の詳細（デバッグ画面向けの複数行テキスト）。 */
    fun report(): String = buildString {
        appendLine(chainedDescription())
        appendLine(summary())
        conflicts.forEach { conflict ->
            val action = when (conflict.action) {
                ConflictAction.ENV_STRIPPED -> "環境変数を除去"
                ConflictAction.REPORTED -> "記録のみ"
                ConflictAction.FIXED -> "自動修正"
            }
            appendLine("- [${conflict.stage.japaneseName}] ${conflict.kind}: ${conflict.detail} ($action)")
        }
        if (deadEnvKeys.isNotEmpty()) {
            appendLine("- 除去した環境変数: " + deadEnvKeys.sorted().joinToString(", "))
        }
    }.trimEnd()
}
