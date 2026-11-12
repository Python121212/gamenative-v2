package app.gamenative.translation

import java.util.Locale

/**
 * 翻訳スタックを決めるための入力。実行時の設定（コンテナ）と、組み上がった環境変数。
 *
 * すべての値は素の文字列・真偽値で、副作用はない。そのため端末なしで単体テストできる
 * （`app/src/test/java/app/gamenative/translation/TranslationStackResolverTest.kt`）。
 */
data class TranslationStackInputs(
    /** `glibc` か `bionic`。bionic は Android の libc 上で wine を動かす層。 */
    val containerVariant: String = "",
    /** wine / proton の識別子（例: `proton-10.0-4-x86_64`）。 */
    val wineVersion: String = "",
    /** wine が arm64ec かどうか。arm64ec のときだけ emulator の選択が効く。 */
    val isArm64Ec: Boolean = false,
    /** `FEXCore` か `Box64`（arm64ec の 32 ビット側エミュレータ）。 */
    val emulator: String = "",
    val box64Version: String = "",
    val fexcoreVersion: String = "",
    /** `dxvk-2.7.1` / `vkd3d-2.14.1` / `wined3d` / `cnc-ddraw` など。 */
    val dxwrapper: String = "",
    /** dxwrapperConfig の `ddrawrapper`（DirectDraw の担当。`none` なら未使用）。 */
    val ddrawWrapper: String = "",
    /** `turnip` / `vortek` / `virgl` / `wrapper` / `adreno` / `sd-8-elite` など。 */
    val graphicsDriver: String = "",
    /** graphicsDriverConfig の `adrenotoolsTurnip`（既定は true）。turnip を Adrenotools 経由で使うか。 */
    val adrenotoolsTurnip: Boolean = true,
    /** graphicsDriverConfig の `adrenotoolsDriver`（例: `vulkan.adreno.so`、`System`）。 */
    val adrenotoolsDriver: String = "",
    /** 起動直前の環境変数（キー → 値）。 */
    val env: Map<String, String> = emptyMap(),
)

/**
 * 多重翻訳を判定する純粋なリゾルバ。
 *
 * 3 つのことを行う:
 * 1. 段ごとに **実際に有効なレイヤー** を 1 つ決める（設定値が正）。
 * 2. 有効でない層の環境変数を [TranslationStackPlan.deadEnvKeys] として列挙する（安全に消せる死んだ層）。
 * 3. 消すと危険な多重化（同じ API を 2 つの層が担当、Vulkan 出力に対する ICD 不在など）を
 *    [TranslationStackPlan.conflicts] として記録する。
 */
object TranslationStackResolver {

    const val CPU_FEXCORE = "fexcore"
    const val CPU_BOX64 = "box64"

    const val WINDOWS_BIONIC = "wine-bionic"
    const val WINDOWS_GLIBC = "wine-glibc"

    const val D3D_DXVK = "dxvk"
    const val D3D_VKD3D = "vkd3d"
    const val D3D_WINED3D = "wined3d"
    const val D3D_CNC_DDRAW = "cnc-ddraw"

    const val GFX_VULKAN = "vulkan"
    const val GFX_GL = "gl"

    // GPU ドライバ（ICD）段。ローダに渡す実装は 1 つだけ。
    const val ICD_TURNIP = "turnip-icd"
    const val ICD_ADRENOTOOLS = "adrenotools-icd"
    const val ICD_VORTEK = "vortek-icd"
    const val ICD_VIRGL = "virgl-gl"

    /** Vulkan ローダに渡す ICD マニフェスト。アプリが imagefs の share 配下で管理しているもの。 */
    const val MANIFEST_FREEDRENO = "freedreno_icd.aarch64.json"
    const val MANIFEST_VORTEK = "vortek_icd.aarch64.json"
    const val MANIFEST_WRAPPER = "wrapper_icd.aarch64.json"

    private val MANAGED_ICD_MANIFESTS = setOf(MANIFEST_FREEDRENO, MANIFEST_VORTEK, MANIFEST_WRAPPER)

    /** Vulkan ローダに ICD を伝える環境変数（新しいローダは `VK_DRIVER_FILES` を優先する）。 */
    private val ICD_LIST_KEYS = listOf("VK_ICD_FILENAMES", "VK_DRIVER_FILES")

    // 各ドライバだけが読む環境変数。
    private val TURNIP_PREFIXES = listOf("TU_")
    private val ADRENOTOOLS_PREFIXES = listOf("ADRENOTOOLS_")
    private val VORTEK_PREFIXES = listOf("VORTEK_")
    private val VIRGL_PREFIXES = listOf("VIRGL_")

    /** FEXCore が読む環境変数。 */
    private val FEX_PREFIXES = listOf("FEX_")

    /** Box64 が読む環境変数（Box86 も同じ系統）。 */
    private val BOX_PREFIXES = listOf("BOX64_", "BOX86_")

    /** Proton / Steam Linux Runtime が読む環境変数。bionic 層では読まれない。 */
    private val PROTON_PREFIXES = listOf("PROTON_", "STEAM_COMPAT_", "UMU_")

    /**
     * bionic でも読まれる例外。Steam の起動経路が両方の層で同じフラグを立てるため、
     * ここに挙げたキーは「Proton 専用」とみなさない。
     */
    private val PROTON_ALLOWLIST = setOf("PROTON_DISABLE_LSTEAMCLIENT")

    private val DXVK_PREFIXES = listOf("DXVK_")
    private val VKD3D_PREFIXES = listOf("VKD3D_")

    private const val CNC_DDRAW_KEY = "CNC_DDRAW_CONFIG_FILE"

    /** DXVK のこの版以降は `d3d8.dll` を同梱するので、単体の D8VK は不要（重複になる）。 */
    private val DXVK_BUNDLED_D3D8_FROM = Version(2, 0)

    private data class Version(val major: Int, val minor: Int) : Comparable<Version> {
        override fun compareTo(other: Version): Int =
            compareValuesBy(this, other, { it.major }, { it.minor })
    }

    /**
     * `dxvk-2.7.1` / `dxvk-1.10.3` / `2.7.1` から版を取り出す。取れなければ null。
     */
    private fun parseDxvkVersion(dxwrapperOrVersion: String): Version? {
        val match = Regex("""(\d+)\.(\d+)""").find(dxwrapperOrVersion) ?: return null
        val major = match.groupValues[1].toIntOrNull() ?: return null
        val minor = match.groupValues[2].toIntOrNull() ?: return null
        return Version(major, minor)
    }

    /**
     * その DXVK 版で単体の D8VK をもう 1 段重ねる必要があるか。
     * 2.0 以降は DXVK 自身が `d3d8.dll` を持つので **不要**（二重の D3D8 担当になる）。
     * 版が読めないときは「必要」と保守的に判断する。
     */
    fun needsStandaloneD8vk(dxvkVersion: String): Boolean {
        val version = parseDxvkVersion(dxvkVersion) ?: return true
        return version < DXVK_BUNDLED_D3D8_FROM
    }

    fun resolve(inputs: TranslationStackInputs): TranslationStackPlan {
        val layers = mutableListOf<TranslationLayer>()
        val dead = linkedSetOf<String>()
        val conflicts = mutableListOf<TranslationConflict>()
        val env = inputs.env

        // ---------------------------------------------------------------- CPU 段
        // arm64ec では emulator の設定が使われ（HODLL = libwow64fex.dll / wowbox64.dll）、
        // それ以外の wine は常に box64 経由でゲストを起動する。
        val cpuId = if (inputs.isArm64Ec && inputs.emulator.equals(CPU_FEXCORE, ignoreCase = true)) {
            CPU_FEXCORE
        } else {
            CPU_BOX64
        }
        layers += TranslationLayer(
            stage = TranslationStage.CPU,
            id = cpuId,
            version = if (cpuId == CPU_FEXCORE) inputs.fexcoreVersion else inputs.box64Version,
        )

        val (liveCpuPrefixes, deadCpuPrefixes) = if (cpuId == CPU_FEXCORE) {
            FEX_PREFIXES to BOX_PREFIXES
        } else {
            BOX_PREFIXES to FEX_PREFIXES
        }
        val deadCpuKeys = keysWithPrefix(env, deadCpuPrefixes)
        if (keysWithPrefix(env, liveCpuPrefixes).isNotEmpty() && deadCpuKeys.isNotEmpty()) {
            conflicts += TranslationConflict(
                stage = TranslationStage.CPU,
                kind = "duplicate-cpu-translator",
                detail = "CPU 翻訳が 2 系統（$cpuId と " +
                    "${if (cpuId == CPU_FEXCORE) CPU_BOX64 else CPU_FEXCORE}）設定されている。" +
                    "有効なのは $cpuId だけなので、もう一方の環境変数を除去する",
                action = ConflictAction.ENV_STRIPPED,
            )
        }
        dead += deadCpuKeys

        // ------------------------------------------------------------ WINDOWS 段
        val isBionic = inputs.containerVariant.equals("bionic", ignoreCase = true)
        layers += TranslationLayer(
            stage = TranslationStage.WINDOWS,
            id = if (isBionic) WINDOWS_BIONIC else WINDOWS_GLIBC,
            version = inputs.wineVersion,
        )
        if (isBionic) {
            // bionic 層は Proton のスクリプト類を一切通らないので、PROTON_* は死んでいる。
            dead += keysWithPrefix(env, PROTON_PREFIXES).filterNot { it in PROTON_ALLOWLIST }
        }

        // ------------------------------------------------------------ DIRECT3D 段
        val dxwrapper = inputs.dxwrapper.lowercase(Locale.ROOT)
        val selectsDxvk = dxwrapper.contains(D3D_DXVK)
        val selectsVkd3d = dxwrapper.contains(D3D_VKD3D)
        // vkd3d を選ぶと、D3D9〜11 用に DXVK の DLL も一緒に展開される（複合計画）。
        val dxvkActive = selectsDxvk || selectsVkd3d
        val vkd3dActive = selectsVkd3d
        val cncActive = dxwrapper.contains(D3D_CNC_DDRAW) ||
            inputs.ddrawWrapper.equals(D3D_CNC_DDRAW, ignoreCase = true)
        val wined3dActive = !dxvkActive

        if (dxvkActive) {
            layers += TranslationLayer(
                stage = TranslationStage.DIRECT3D,
                id = D3D_DXVK,
                version = inputs.dxwrapper.let(::dxvkVersionFromId),
            )
        }
        if (vkd3dActive) {
            layers += TranslationLayer(
                stage = TranslationStage.DIRECT3D,
                id = D3D_VKD3D,
                version = inputs.dxwrapper.let(::vkd3dVersionFromId),
            )
        }
        if (cncActive) {
            layers += TranslationLayer(stage = TranslationStage.DIRECT3D, id = D3D_CNC_DDRAW)
        }
        if (wined3dActive) {
            layers += TranslationLayer(stage = TranslationStage.DIRECT3D, id = D3D_WINED3D)
        }

        if (!dxvkActive) dead += keysWithPrefix(env, DXVK_PREFIXES)
        if (!vkd3dActive) dead += keysWithPrefix(env, VKD3D_PREFIXES)
        if (!cncActive) {
            // DirectDraw を DXVK/wined3d が担当しているなら cnc-ddraw の設定ファイル指定は無効。
            if (env.containsKey(CNC_DDRAW_KEY)) dead += CNC_DDRAW_KEY
        }

        // 複合計画なのに DXVK 側だけ設定されていない（＝設定されないまま動く第 2 レイヤー）。
        if (vkd3dActive && keysWithPrefix(env, DXVK_PREFIXES).isEmpty()) {
            conflicts += TranslationConflict(
                stage = TranslationStage.DIRECT3D,
                kind = "vkd3d-composite-unconfigured-dxvk",
                detail = "vkd3d の計画は D3D9〜11 用に DXVK も読み込むが、DXVK_* が設定されていない。" +
                    "シェーダーキャッシュ・フレームレート制限・dxvk.conf が効かない状態で第 2 レイヤーが動く",
                action = ConflictAction.FIXED,
            )
        }

        // WINEDLLOVERRIDES が DXVK の DLL を builtin(wined3d) に固定していると、
        // 入れた DXVK は使われず wined3d が二重に走る。
        val shadowed = builtinShadowedD3dDlls(env["WINEDLLOVERRIDES"].orEmpty())
        if (dxvkActive && shadowed.isNotEmpty()) {
            conflicts += TranslationConflict(
                stage = TranslationStage.DIRECT3D,
                kind = "builtin-override-shadows-dxvk",
                detail = "WINEDLLOVERRIDES が ${shadowed.joinToString(", ")} を builtin に固定している。" +
                    "DXVK を入れた意味がなくなり、wined3d 側の翻訳が二重に走る",
                action = ConflictAction.REPORTED,
            )
        }

        // -------------------------------------------------------- GRAPHICS_API 段
        // virgl は GL 専用（Vulkan ICD を出さない）。それ以外は Vulkan ICD が GPU に直結する。
        val glOnly = inputs.graphicsDriver.equals("virgl", ignoreCase = true)
        layers += TranslationLayer(
            stage = TranslationStage.GRAPHICS_API,
            id = if (glOnly) GFX_GL else GFX_VULKAN,
            version = inputs.graphicsDriver,
        )

        if (glOnly && dxvkActive) {
            conflicts += TranslationConflict(
                stage = TranslationStage.GRAPHICS_API,
                kind = "vulkan-translator-without-icd",
                detail = "D3D→Vulkan の翻訳（DXVK/VKD3D）を選んでいるが、グラフィックスドライバは GL 専用の virgl。" +
                    "Vulkan を受け取る先が無いので wined3d（D3D→GL）に切り替える必要がある",
                action = ConflictAction.REPORTED,
            )
        }
        val zink = env["GALLIUM_DRIVER"].orEmpty().equals("zink", ignoreCase = true)
        if (!glOnly && zink && dxvkActive) {
            conflicts += TranslationConflict(
                stage = TranslationStage.GRAPHICS_API,
                kind = "extra-gl-bridge",
                detail = "D3D→Vulkan（DXVK）が直結している一方で、GL 用に Vulkan→GL の zink も有効になっている。" +
                    "OpenGL を使うタイトルには必要なので残すが、D3D の経路には余分な段である",
                action = ConflictAction.REPORTED,
            )
        }

        // ------------------------------------------------------------ GPU_DRIVER 段
        // Vulkan ローダが読み込む ICD は 1 つだけ。ドライバ設定から期待する ICD を決め、
        // 別の ICD を指している環境変数・別ドライバ専用の環境変数を落とす。
        val graphicsDriver = inputs.graphicsDriver.lowercase(Locale.ROOT)
        val icd = resolveIcd(graphicsDriver, inputs.adrenotoolsTurnip)
        layers += TranslationLayer(
            stage = TranslationStage.GPU_DRIVER,
            id = icd.id,
            version = inputs.adrenotoolsDriver.ifBlank { inputs.graphicsDriver },
        )

        // 選ばれなかったドライバ専用の環境変数を除去する。
        if (icd.id != ICD_TURNIP) dead += keysWithPrefix(env, TURNIP_PREFIXES)
        if (icd.id != ICD_ADRENOTOOLS && icd.id != ICD_VORTEK) dead += keysWithPrefix(env, ADRENOTOOLS_PREFIXES)
        if (icd.id != ICD_VORTEK) dead += keysWithPrefix(env, VORTEK_PREFIXES)
        if (icd.id != ICD_VIRGL) dead += keysWithPrefix(env, VIRGL_PREFIXES)

        val families = listOf(
            ICD_TURNIP to keysWithPrefix(env, TURNIP_PREFIXES),
            ICD_ADRENOTOOLS to keysWithPrefix(env, ADRENOTOOLS_PREFIXES),
            ICD_VORTEK to keysWithPrefix(env, VORTEK_PREFIXES),
            ICD_VIRGL to keysWithPrefix(env, VIRGL_PREFIXES),
        ).filter { it.second.isNotEmpty() }
        // vortek は Adrenotools のドライバ .so を読み込むので、この 2 つは同居してよい。
        val liveFamilies = when (icd.id) {
            ICD_TURNIP -> setOf(ICD_TURNIP)
            ICD_VORTEK -> setOf(ICD_VORTEK, ICD_ADRENOTOOLS)
            ICD_ADRENOTOOLS -> setOf(ICD_ADRENOTOOLS)
            else -> setOf(ICD_VIRGL)
        }
        val livePresent = families.filter { it.first in liveFamilies }
        val deadPresent = families.filter { it.first !in liveFamilies }
        if (livePresent.isNotEmpty() && deadPresent.isNotEmpty()) {
            conflicts += TranslationConflict(
                stage = TranslationStage.GPU_DRIVER,
                kind = "duplicate-gpu-driver-providers",
                detail = "GPU ドライバが 2 系統以上設定されている（有効: ${icd.id} / 未使用: " +
                    deadPresent.joinToString(", ") { it.first } + "）。未使用側の環境変数を除去する",
                action = ConflictAction.ENV_STRIPPED,
            )
        }

        // ICD を指す環境変数の中身を検査する（複数指定・別マニフェストの指定）。
        val icdValues = ICD_LIST_KEYS.mapNotNull { key -> env[key]?.let { key to it } }
        val managedEntries = icdValues.flatMap { (key, value) ->
            splitIcdList(value).filter { basename(it) in MANAGED_ICD_MANIFESTS }.map { key to it }
        }
        if (icdValues.size > 1 || managedEntries.size > 1) {
            conflicts += TranslationConflict(
                stage = TranslationStage.GPU_DRIVER,
                kind = "icd-manifest-duplicate",
                detail = "Vulkan ローダに渡す ICD が複数指定されている（" +
                    icdValues.joinToString(", ") { "${it.first}=${basename(it.second)}" } +
                    "）。1 つに固定する",
                action = ConflictAction.FIXED,
            )
        }
        val expectedManifest = icd.manifest
        val mismatched = managedEntries.filter { (_, value) -> basename(value) != expectedManifest }
        if (expectedManifest != null && mismatched.isNotEmpty()) {
            conflicts += TranslationConflict(
                stage = TranslationStage.GPU_DRIVER,
                kind = "icd-manifest-mismatch",
                detail = "選択中の GPU ドライバ（${icd.id}）は $expectedManifest を使うが、" +
                    "環境変数は ${mismatched.joinToString(", ") { basename(it.second) }} を指している",
                action = ConflictAction.FIXED,
            )
        }
        if (icd.id == ICD_VIRGL && icdValues.isNotEmpty()) {
            conflicts += TranslationConflict(
                stage = TranslationStage.GPU_DRIVER,
                kind = "vulkan-icd-on-gl-driver",
                detail = "グラフィックスドライバは GL 専用の virgl なのに、Vulkan の ICD が設定されている。" +
                    "GL 経路では使われないが、ユーティリティが Vulkan を使う可能性があるため残す",
                action = ConflictAction.REPORTED,
            )
        }

        return TranslationStackPlan(
            layers = layers,
            deadEnvKeys = dead.toList(),
            conflicts = conflicts,
            expectedIcdManifest = expectedManifest,
        )
    }

    /** GPU ドライバ設定から、この起動で唯一有効な ICD を決める。 */
    private data class Icd(val id: String, val manifest: String?)

    private fun resolveIcd(graphicsDriver: String, adrenotoolsTurnip: Boolean): Icd = when {
        // virgl は virpipe（GL）で、Vulkan を受け取る先が無い。
        graphicsDriver == "virgl" -> Icd(ICD_VIRGL, null)
        // turnip 直結（Adrenotools 無効）は freedreno ICD をローダに直接渡す。
        graphicsDriver == "turnip" && !adrenotoolsTurnip -> Icd(ICD_TURNIP, MANIFEST_FREEDRENO)
        // vortek は in-process のレンダラ。GL 側は zink が Vulkan を使うため wrapper ICD が残る。
        graphicsDriver == "vortek" || graphicsDriver == "adreno" || graphicsDriver == "sd-8-elite" ->
            Icd(ICD_VORTEK, MANIFEST_WRAPPER)
        // wrapper（Adrenotools）: wrapper ICD がドライバ .so を吊る。
        else -> Icd(ICD_ADRENOTOOLS, MANIFEST_WRAPPER)
    }

    private fun splitIcdList(value: String): List<String> =
        value.split(':', ',').map { it.trim() }.filter { it.isNotEmpty() }

    private fun basename(path: String): String = path.substringAfterLast('/').substringAfterLast('\\')

    // ------------------------------------------------------------------
    // 適用側（TranslationStackApplier）が ICD を 1 つに固定するための小さなヘルパ。
    // ------------------------------------------------------------------

    /** アプリが imagefs の share 配下で管理している ICD マニフェストか。 */
    fun isManagedIcdManifest(pathOrName: String): Boolean = basename(pathOrName) in MANAGED_ICD_MANIFESTS

    /** `A:B` / `A,B`（ローダは複数指定を受け付ける）を 1 件ずつに分ける。 */
    fun icdListEntries(value: String): List<String> = splitIcdList(value)

    /** パスからファイル名だけを取り出す。 */
    fun icdManifestBasename(path: String): String = basename(path)

    /** Vulkan ローダに ICD を伝える環境変数（新しいローダは `VK_DRIVER_FILES` を優先する）。 */
    fun icdListKeys(): List<String> = ICD_LIST_KEYS

    private fun keysWithPrefix(env: Map<String, String>, prefixes: List<String>): List<String> =
        env.keys.filter { key -> prefixes.any { key.startsWith(it) } }.sorted()

    /** `dxvk-2.7.1` → `2.7.1`。版が読めなければ空。 */
    private fun dxvkVersionFromId(dxwrapper: String): String =
        Regex("""dxvk-([0-9][^+,\s]*)""").find(dxwrapper)?.groupValues?.get(1)?.substringBefore('-') ?: ""

    private fun vkd3dVersionFromId(dxwrapper: String): String =
        Regex("""vkd3d-([0-9][^+,\s]*)""").find(dxwrapper)?.groupValues?.get(1) ?: ""

    /**
     * `d3d9=b;d3d11=b` のように builtin へ固定されている DLL を返す（`n,b` のように native を含む指定は対象外）。
     */
    private fun builtinShadowedD3dDlls(overrides: String): List<String> {
        if (overrides.isBlank()) return emptyList()
        val managed = listOf("d3d8", "d3d9", "d3d10core", "d3d11", "d3d12", "dxgi")
        return overrides.split(';')
            .mapNotNull { entry ->
                val parts = entry.split('=')
                if (parts.size != 2) return@mapNotNull null
                val dll = parts[0].trim().lowercase(Locale.ROOT).removeSuffix(".dll")
                val setting = parts[1].trim().lowercase(Locale.ROOT)
                if (dll !in managed) return@mapNotNull null
                val builtin = setting.contains('b')
                val native = setting.contains('n')
                if (builtin && !native) dll else null
            }
            .distinct()
    }
}
