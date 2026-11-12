package app.gamenative.translation

import com.winlator.container.Container
import com.winlator.core.KeyValueSet
import com.winlator.core.envvars.EnvVars
import timber.log.Timber

/**
 * 起動直前の環境変数に [TranslationStackResolver] の判定を適用する。
 *
 * 行うこと:
 * - 死んでいる層の環境変数（例: FEXCore で動くのに残っている `BOX64_*`、wined3d なのに残っている `DXVK_*`）を削除する。
 * - 決定した計画を 1 行でログに出す（`translation-stack cpu=… win=… d3d=… gfx=… hops=… dedup=…`）。
 * - 除去すると挙動が変わる多重化は警告として残す。
 *
 * 失敗しても起動は止めない（環境変数をそのまま使う）。
 */
object TranslationStackApplier {

    private const val TAG = "TranslationStack"

    /**
     * @param container 起動対象のコンテナ
     * @param envVars 起動直前の環境変数（このオブジェクトを直接書き換える）
     * @param isArm64Ec wine が arm64ec かどうか（`xServerState.value.wineInfo.isArm64EC()`）
     * @param dxwrapperId 実際に適用された dxwrapper（`xserverState.value.dxwrapper` など。null なら container の値）
     * @param shareDirPath imagefs の share ディレクトリ（ICD マニフェストの置き場所。null なら書き換えない）
     */
    @JvmStatic
    @JvmOverloads
    fun applyToFinalEnv(
        container: Container?,
        envVars: EnvVars,
        isArm64Ec: Boolean,
        dxwrapperId: String? = null,
        shareDirPath: String? = null,
    ): TranslationStackPlan? {
        if (container == null) return null
        return try {
            val plan = TranslationStackResolver.resolve(inputsOf(container, envVars, isArm64Ec, dxwrapperId))
            removeDeadKeys(envVars, plan)
            fixIcdEnv(envVars, plan, shareDirPath)
            logPlan(plan)
            plan
        } catch (error: Throwable) {
            // 計画に失敗してもゲームは起動できる。環境は触らずにそのまま使う。
            Timber.tag(TAG).w(error, "翻訳スタックの判定に失敗したため、環境変数は変更しません")
            null
        }
    }

    /** 環境変数を書き換えずに計画だけを得る（デバッグ画面・テスト用）。 */
    @JvmStatic
    @JvmOverloads
    fun planFor(
        container: Container?,
        envVars: EnvVars,
        isArm64Ec: Boolean,
        dxwrapperId: String? = null,
    ): TranslationStackPlan? {
        if (container == null) return null
        return try {
            TranslationStackResolver.resolve(inputsOf(container, envVars, isArm64Ec, dxwrapperId))
        } catch (error: Throwable) {
            Timber.tag(TAG).w(error, "翻訳スタックの判定に失敗しました")
            null
        }
    }

    private fun inputsOf(
        container: Container,
        envVars: EnvVars,
        isArm64Ec: Boolean,
        dxwrapperId: String?,
    ): TranslationStackInputs {
        val env = LinkedHashMap<String, String>()
        for (key in envVars) env[key] = envVars.get(key)

        val ddrawWrapper = try {
            KeyValueSet(container.getDXWrapperConfig()).get("ddrawrapper")
        } catch (error: Throwable) {
            Timber.tag(TAG).w(error, "dxwrapperConfig を解析できませんでした")
            ""
        }

        var adrenotoolsTurnip = true
        var adrenotoolsDriver = ""
        try {
            val driverConfig = KeyValueSet(container.getGraphicsDriverConfig())
            adrenotoolsTurnip = driverConfig.get("adrenotoolsTurnip", "1") != "0"
            adrenotoolsDriver = driverConfig.get("adrenotoolsDriver")
        } catch (error: Throwable) {
            Timber.tag(TAG).w(error, "graphicsDriverConfig を解析できませんでした")
        }

        return TranslationStackInputs(
            containerVariant = container.getContainerVariant().orEmpty(),
            wineVersion = container.getWineVersion().orEmpty(),
            isArm64Ec = isArm64Ec,
            emulator = container.getEmulator().orEmpty(),
            box64Version = container.getBox64Version().orEmpty(),
            fexcoreVersion = container.getFEXCoreVersion().orEmpty(),
            dxwrapper = dxwrapperId ?: container.getDXWrapper().orEmpty(),
            ddrawWrapper = ddrawWrapper,
            graphicsDriver = container.getGraphicsDriver().orEmpty(),
            adrenotoolsTurnip = adrenotoolsTurnip,
            adrenotoolsDriver = adrenotoolsDriver,
            env = env,
        )
    }

    private fun removeDeadKeys(envVars: EnvVars, plan: TranslationStackPlan) {
        plan.deadEnvKeys.forEach { key -> envVars.remove(key) }
    }

    /**
     * Vulkan ローダに渡す ICD を 1 つに固定する。
     *
     * - アプリが管理しているマニフェスト（freedreno / vortek / wrapper）を指している場合だけ書き換える。
     *   ゲームスコープ等のセッション固有 ICD には触らない。
     * - 複数指定（`VK_ICD_FILENAMES` と `VK_DRIVER_FILES` の併用、`:` 区切りの複数）は 1 件に畳む。
     */
    private fun fixIcdEnv(envVars: EnvVars, plan: TranslationStackPlan, shareDirPath: String?) {
        val expected = plan.expectedIcdManifest
        if (expected != null) {
            TranslationStackResolver.icdListKeys().forEach { key ->
                if (!envVars.has(key)) return@forEach
                val managed = TranslationStackResolver.icdListEntries(envVars.get(key))
                    .firstOrNull { TranslationStackResolver.isManagedIcdManifest(it) } ?: return@forEach
                val dir = managed.substringBeforeLast('/', "")
                val targetDir = when {
                    dir.endsWith("/vulkan/icd.d") -> dir
                    !shareDirPath.isNullOrBlank() -> "$shareDirPath/vulkan/icd.d"
                    else -> dir
                }
                val normalized = if (targetDir.isEmpty()) expected else "$targetDir/$expected"
                if (normalized != envVars.get(key)) {
                    Timber.tag(TAG).i("ICD を 1 つに固定: $key=${envVars.get(key)} → $normalized")
                    envVars.put(key, normalized)
                }
            }
        }
        // ローダは両方を見るので、片方だけ残す（既存コードは VK_ICD_FILENAMES を使っている）。
        if (envVars.has("VK_ICD_FILENAMES") && envVars.has("VK_DRIVER_FILES")) {
            Timber.tag(TAG).i("ICD の指定が二重なので VK_DRIVER_FILES を削除（VK_ICD_FILENAMES に統一）")
            envVars.remove("VK_DRIVER_FILES")
        }
    }

    private fun logPlan(plan: TranslationStackPlan) {
        Timber.tag(TAG).i(plan.summary())
        Timber.tag(TAG).i(plan.chainedDescription())
        plan.conflicts.forEach { conflict ->
            val message = "[${conflict.stage.japaneseName}] ${conflict.kind}: ${conflict.detail}"
            if (conflict.action == ConflictAction.REPORTED) {
                Timber.tag(TAG).w(message)
            } else {
                Timber.tag(TAG).i(message)
            }
        }
        if (!plan.isSingleLayerPerStage) {
            Timber.tag(TAG).w("同じ段に複数の翻訳レイヤーが残っています: ${plan.report()}")
        }
    }
}
