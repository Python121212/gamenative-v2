package app.gamenative.translation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 多重翻訳の判定。端末なしで検証できるよう、リゾルバは純粋関数にしてある。
 */
class TranslationStackResolverTest {

    private fun inputs(
        containerVariant: String = "glibc",
        wineVersion: String = "proton-10.0-4-x86_64",
        isArm64Ec: Boolean = false,
        emulator: String = "FEXCore",
        dxwrapper: String = "dxvk-2.7.1",
        ddrawWrapper: String = "none",
        graphicsDriver: String = "turnip",
        adrenotoolsTurnip: Boolean = true,
        adrenotoolsDriver: String = "",
        env: Map<String, String> = emptyMap(),
    ) = TranslationStackInputs(
        containerVariant = containerVariant,
        wineVersion = wineVersion,
        isArm64Ec = isArm64Ec,
        emulator = emulator,
        box64Version = "0.3.4",
        fexcoreVersion = "2508",
        dxwrapper = dxwrapper,
        ddrawWrapper = ddrawWrapper,
        graphicsDriver = graphicsDriver,
        adrenotoolsTurnip = adrenotoolsTurnip,
        adrenotoolsDriver = adrenotoolsDriver,
        env = env,
    )

    // ------------------------------------------------------------------ CPU 段

    @Test
    fun `FEXCore が有効なら Box64 の環境変数を落とす`() {
        val plan = TranslationStackResolver.resolve(
            inputs(
                isArm64Ec = true,
                emulator = "FEXCore",
                env = mapOf(
                    "FEX_TSOENABLED" to "1",
                    "FEX_MULTIBLOCK" to "1",
                    "BOX64_DYNAREC" to "1",
                    "BOX64_LOG" to "1",
                ),
            ),
        )

        assertEquals("fexcore", plan.layer(TranslationStage.CPU)?.id)
        assertEquals(listOf("BOX64_DYNAREC", "BOX64_LOG"), plan.deadEnvKeys)
        assertTrue(
            "CPU 段の多重化は記録される",
            plan.conflicts.any { it.kind == "duplicate-cpu-translator" && it.stage == TranslationStage.CPU },
        )
        assertTrue(plan.isSingleLayerPerStage)
    }

    @Test
    fun `arm64ec でない wine は box64 を使うので FEX の環境変数は死んでいる`() {
        val plan = TranslationStackResolver.resolve(
            inputs(
                isArm64Ec = false,
                emulator = "FEXCore", // 設定は残っていても、この wine では使われない
                env = mapOf(
                    "FEX_TSOENABLED" to "1",
                    "BOX64_DYNAREC" to "1",
                ),
            ),
        )

        assertEquals("box64", plan.layer(TranslationStage.CPU)?.id)
        assertEquals(listOf("FEX_TSOENABLED"), plan.deadEnvKeys)
        // 生きている層のキーだけが残る
        assertFalse(plan.deadEnvKeys.contains("BOX64_DYNAREC"))
    }

    @Test
    fun `arm64ec で Box64 を選べば FEX を落とす`() {
        val plan = TranslationStackResolver.resolve(
            inputs(
                isArm64Ec = true,
                emulator = "Box64",
                env = mapOf("FEX_X87REDUCEDPRECISION" to "1", "BOX64_DYNAREC" to "1"),
            ),
        )

        assertEquals("box64", plan.layer(TranslationStage.CPU)?.id)
        assertEquals(listOf("FEX_X87REDUCEDPRECISION"), plan.deadEnvKeys)
    }

    // ------------------------------------------------------------- WINDOWS 段

    @Test
    fun `bionic では Proton の環境変数を落とすが Steam 用のフラグは残す`() {
        val plan = TranslationStackResolver.resolve(
            inputs(
                containerVariant = "bionic",
                env = mapOf(
                    "PROTON_LOG" to "1",
                    "STEAM_COMPAT_DATA_PATH" to "/data/x",
                    "PROTON_DISABLE_LSTEAMCLIENT" to "1",
                ),
            ),
        )

        assertEquals("wine-bionic", plan.layer(TranslationStage.WINDOWS)?.id)
        assertEquals(listOf("PROTON_LOG", "STEAM_COMPAT_DATA_PATH"), plan.deadEnvKeys)
    }

    @Test
    fun `glibc では Proton の環境変数を落とさない`() {
        val plan = TranslationStackResolver.resolve(
            inputs(containerVariant = "glibc", env = mapOf("PROTON_LOG" to "1")),
        )

        assertEquals("wine-glibc", plan.layer(TranslationStage.WINDOWS)?.id)
        assertTrue(plan.deadEnvKeys.isEmpty())
    }

    // ------------------------------------------------------------ DIRECT3D 段

    @Test
    fun `wined3d を選んだら DXVK と VKD3D と cnc-ddraw の設定は死んでいる`() {
        val plan = TranslationStackResolver.resolve(
            inputs(
                dxwrapper = "wined3d",
                env = mapOf(
                    "DXVK_STATE_CACHE_PATH" to "/data/cache",
                    "VKD3D_FEATURE_LEVEL" to "12_1",
                    "CNC_DDRAW_CONFIG_FILE" to "C:\\ddraw.ini",
                ),
            ),
        )

        assertEquals(
            listOf("DXVK_STATE_CACHE_PATH", "VKD3D_FEATURE_LEVEL", "CNC_DDRAW_CONFIG_FILE"),
            plan.deadEnvKeys,
        )
        assertEquals(listOf("wined3d"), plan.layersOf(TranslationStage.DIRECT3D).map { it.id })
        assertTrue(plan.isSingleLayerPerStage)
    }

    @Test
    fun `DXVK を選んだら VKD3D の設定は死んでいる`() {
        val plan = TranslationStackResolver.resolve(
            inputs(
                dxwrapper = "dxvk-2.7.1",
                env = mapOf("DXVK_STATE_CACHE_PATH" to "/data/cache", "VKD3D_FEATURE_LEVEL" to "12_1"),
            ),
        )

        assertEquals(listOf("VKD3D_FEATURE_LEVEL"), plan.deadEnvKeys)
        val dxvk = plan.layer(TranslationStage.DIRECT3D)
        assertEquals("dxvk", dxvk?.id)
        assertEquals("2.7.1", dxvk?.version)
    }

    @Test
    fun `vkd3d の計画は DXVK を相棒として持つので DXVK の設定を落とさない`() {
        val plan = TranslationStackResolver.resolve(
            inputs(
                dxwrapper = "vkd3d-2.14.1",
                env = mapOf(
                    "DXVK_STATE_CACHE_PATH" to "/data/cache",
                    "VKD3D_FEATURE_LEVEL" to "12_1",
                ),
            ),
        )

        assertTrue(plan.deadEnvKeys.isEmpty())
        assertEquals(
            listOf("dxvk", "vkd3d"),
            plan.layersOf(TranslationStage.DIRECT3D).map { it.id },
        )
        // API の担当が分かれている 2 レイヤーは 1 本の計画として許容する
        assertTrue(plan.isSingleLayerPerStage)
        assertTrue(plan.conflicts.none { it.kind == "vkd3d-composite-unconfigured-dxvk" })
    }

    @Test
    fun `vkd3d なのに DXVK の設定が無ければ未設定の第 2 レイヤーとして記録する`() {
        val plan = TranslationStackResolver.resolve(
            inputs(dxwrapper = "vkd3d-2.14.1", env = mapOf("VKD3D_FEATURE_LEVEL" to "12_1")),
        )

        assertTrue(plan.conflicts.any { it.kind == "vkd3d-composite-unconfigured-dxvk" })
    }

    @Test
    fun `cnc-ddraw を ddraw 担当にしたときは設定を残す`() {
        val plan = TranslationStackResolver.resolve(
            inputs(
                dxwrapper = "dxvk-2.7.1",
                ddrawWrapper = "cnc-ddraw",
                env = mapOf("CNC_DDRAW_CONFIG_FILE" to "C:\\ddraw.ini"),
            ),
        )

        assertTrue(plan.deadEnvKeys.isEmpty())
        assertEquals(
            listOf("dxvk", "cnc-ddraw"),
            plan.layersOf(TranslationStage.DIRECT3D).map { it.id },
        )
    }

    @Test
    fun `WINEDLLOVERRIDES が builtin 固定なら DXVK と二重になるので記録する`() {
        val shadowed = TranslationStackResolver.resolve(
            inputs(dxwrapper = "dxvk-2.7.1", env = mapOf("WINEDLLOVERRIDES" to "d3d9=b;d3d11=b")),
        )
        assertTrue(shadowed.conflicts.any { it.kind == "builtin-override-shadows-dxvk" })

        val native = TranslationStackResolver.resolve(
            inputs(dxwrapper = "dxvk-2.7.1", env = mapOf("WINEDLLOVERRIDES" to "d3d9=n,b")),
        )
        assertTrue(native.conflicts.none { it.kind == "builtin-override-shadows-dxvk" })
    }

    // -------------------------------------------------------- GRAPHICS_API 段

    @Test
    fun `Vulkan 直結のドライバならグラフィックス段は vulkan`() {
        val plan = TranslationStackResolver.resolve(inputs(graphicsDriver = "turnip"))

        assertEquals("vulkan", plan.layer(TranslationStage.GRAPHICS_API)?.id)
        assertEquals("turnip", plan.layer(TranslationStage.GRAPHICS_API)?.version)
        assertTrue(plan.conflicts.none { it.kind == "vulkan-translator-without-icd" })
    }

    @Test
    fun `GL 専用ドライバに DXVK を組み合わせたら記録する`() {
        val plan = TranslationStackResolver.resolve(
            inputs(dxwrapper = "dxvk-2.7.1", graphicsDriver = "virgl"),
        )

        assertEquals("gl", plan.layer(TranslationStage.GRAPHICS_API)?.id)
        assertTrue(plan.conflicts.any { it.kind == "vulkan-translator-without-icd" })
    }

    @Test
    fun `DXVK と zink が同時なら余分な GL ブリッジとして記録するが環境は消さない`() {
        val env = mapOf("GALLIUM_DRIVER" to "zink")
        val plan = TranslationStackResolver.resolve(inputs(dxwrapper = "dxvk-2.7.1", env = env))

        assertTrue(plan.conflicts.any { it.kind == "extra-gl-bridge" })
        assertTrue(plan.deadEnvKeys.isEmpty())
        // 記録のみで、zink を使う OpenGL タイトルのために残す
        assertEquals("zink", env["GALLIUM_DRIVER"])
    }

    // ------------------------------------------------------------------ その他

    @Test
    fun `計画は常に 1 段 1 レイヤーに収束する`() {
        val variants = listOf(
            "dxvk-2.7.1",
            "vkd3d-2.14.1",
            "wined3d",
            "cnc-ddraw",
        )
        variants.forEach { dxwrapper ->
            listOf("turnip", "virgl", "vortek").forEach { driver ->
                listOf(true, false).forEach { arm64 ->
                    val plan = TranslationStackResolver.resolve(
                        inputs(
                            isArm64Ec = arm64,
                            emulator = if (arm64) "FEXCore" else "Box64",
                            dxwrapper = dxwrapper,
                            graphicsDriver = driver,
                        ),
                    )
                    assertTrue("$dxwrapper/$driver/arm64=$arm64", plan.isSingleLayerPerStage)
                    assertNotNull(plan.layer(TranslationStage.CPU))
                    assertNotNull(plan.layer(TranslationStage.WINDOWS))
                    assertNotNull(plan.layer(TranslationStage.GRAPHICS_API))
                    assertNotNull(plan.layer(TranslationStage.GPU_DRIVER))
                    assertTrue(plan.hopCount in 4..5)
                }
            }
        }
    }


    // ------------------------------------------------------------ GPU_DRIVER 段

    @Test
    fun `turnip を Adrenotools 無効で使うなら freedreno ICD を 1 つだけ渡す`() {
        val plan = TranslationStackResolver.resolve(
            inputs(
                graphicsDriver = "turnip",
                adrenotoolsTurnip = false,
                env = mapOf(
                    "TU_DEBUG" to "sysmem",
                    "TU_OVERRIDE_HEAP_SIZE" to "4096",
                    "ADRENOTOOLS_DRIVER_NAME" to "vulkan.adreno.so",
                ),
            ),
        )

        assertEquals("turnip-icd", plan.layer(TranslationStage.GPU_DRIVER)?.id)
        assertEquals(
            TranslationStackResolver.MANIFEST_FREEDRENO,
            plan.expectedIcdManifest,
        )
        // Adrenotools は使わないので、その設定は死んでいる
        assertEquals(listOf("ADRENOTOOLS_DRIVER_NAME"), plan.deadEnvKeys)
    }

    @Test
    fun `turnip を Adrenotools 経由で使うなら wrapper ICD になり TU_ は落ちる`() {
        val plan = TranslationStackResolver.resolve(
            inputs(
                graphicsDriver = "turnip",
                adrenotoolsTurnip = true,
                adrenotoolsDriver = "vulkan.adreno.so",
                env = mapOf(
                    "TU_DEBUG" to "sysmem",
                    "ADRENOTOOLS_DRIVER_NAME" to "vulkan.adreno.so",
                    "ADRENOTOOLS_DRIVER_PATH" to "/data/adrenotools/v762",
                ),
            ),
        )

        assertEquals("adrenotools-icd", plan.layer(TranslationStage.GPU_DRIVER)?.id)
        assertEquals(TranslationStackResolver.MANIFEST_WRAPPER, plan.expectedIcdManifest)
        assertEquals(listOf("TU_DEBUG"), plan.deadEnvKeys)
        assertTrue(plan.conflicts.any { it.kind == "duplicate-gpu-driver-providers" })
    }

    @Test
    fun `vortek は Adrenotools と同居できるが turnip の設定は落とす`() {
        val plan = TranslationStackResolver.resolve(
            inputs(
                graphicsDriver = "vortek",
                env = mapOf(
                    "VORTEK_SERVER_PATH" to "/data/tmp/.vortek",
                    "ADRENOTOOLS_DRIVER_NAME" to "vulkan.adreno.so",
                    "TU_DEBUG" to "sysmem",
                    "VIRGL_NO_READBACK" to "true",
                ),
            ),
        )

        assertEquals("vortek-icd", plan.layer(TranslationStage.GPU_DRIVER)?.id)
        assertEquals(listOf("TU_DEBUG", "VIRGL_NO_READBACK"), plan.deadEnvKeys)
        // vortek は Adrenotools のドライバを読み込むので、その 2 系統は同居してよい。
        // ただし turnip/virgl の設定が残っていれば「別ドライバの残骸」として記録する。
        val conflict = plan.conflicts.firstOrNull { it.kind == "duplicate-gpu-driver-providers" }
        assertNotNull(conflict)
        assertTrue(conflict!!.detail.contains("vortek-icd"))
    }

    @Test
    fun `virgl は Vulkan を使わないので ICD は null で Vulkan 系の設定は落とす`() {
        val plan = TranslationStackResolver.resolve(
            inputs(
                dxwrapper = "wined3d",
                graphicsDriver = "virgl",
                env = mapOf(
                    "VIRGL_SERVER_PATH" to "/data/virgl",
                    "TU_DEBUG" to "sysmem",
                    "ADRENOTOOLS_DRIVER_NAME" to "vulkan.adreno.so",
                    "VK_ICD_FILENAMES" to "/data/share/vulkan/icd.d/wrapper_icd.aarch64.json",
                ),
            ),
        )

        assertEquals("virgl-gl", plan.layer(TranslationStage.GPU_DRIVER)?.id)
        assertNull(plan.expectedIcdManifest)
        assertEquals(listOf("TU_DEBUG", "ADRENOTOOLS_DRIVER_NAME"), plan.deadEnvKeys)
        // Vulkan の ICD は残す（ユーティリティが使う可能性がある）が、記録はする
        assertTrue(plan.conflicts.any { it.kind == "vulkan-icd-on-gl-driver" })
    }

    @Test
    fun `選んだドライバと別の ICD を指していたら固定対象として記録する`() {
        val plan = TranslationStackResolver.resolve(
            inputs(
                graphicsDriver = "turnip",
                adrenotoolsTurnip = true,
                env = mapOf(
                    "VK_ICD_FILENAMES" to "/data/share/vulkan/icd.d/freedreno_icd.aarch64.json",
                ),
            ),
        )

        assertEquals(TranslationStackResolver.MANIFEST_WRAPPER, plan.expectedIcdManifest)
        val conflict = plan.conflicts.firstOrNull { it.kind == "icd-manifest-mismatch" }
        assertNotNull(conflict)
        assertEquals(ConflictAction.FIXED, conflict?.action)
    }

    @Test
    fun `ICD の指定が二重なら 1 つに畳む対象として記録する`() {
        val plan = TranslationStackResolver.resolve(
            inputs(
                graphicsDriver = "turnip",
                env = mapOf(
                    "VK_ICD_FILENAMES" to "/data/share/vulkan/icd.d/wrapper_icd.aarch64.json",
                    "VK_DRIVER_FILES" to "/data/share/vulkan/icd.d/wrapper_icd.aarch64.json",
                ),
            ),
        )

        val conflict = plan.conflicts.firstOrNull { it.kind == "icd-manifest-duplicate" }
        assertNotNull(conflict)
        assertEquals(ConflictAction.FIXED, conflict?.action)
    }

    @Test
    fun `アプリ管理外のセッション ICD は書き換え対象にしない`() {
        val plan = TranslationStackResolver.resolve(
            inputs(
                graphicsDriver = "turnip",
                env = mapOf(
                    "VK_ICD_FILENAMES" to "/data/user/0/app.gamenative/files/gamescope/share/vulkan/icd.d/adrenotools_gamescope.aarch64.json",
                ),
            ),
        )

        assertTrue(plan.conflicts.none { it.kind == "icd-manifest-mismatch" })
        assertTrue(plan.conflicts.none { it.kind == "icd-manifest-duplicate" })
    }

    @Test
    fun `DXVK 2 系は d3d8 を同梱するので D8VK を重ねない`() {
        assertFalse(TranslationStackResolver.needsStandaloneD8vk("2.7.1"))
        assertFalse(TranslationStackResolver.needsStandaloneD8vk("2.4.1-gplasync"))
        // 1 系は d3d8.dll を持たないので D8VK が必要
        assertTrue(TranslationStackResolver.needsStandaloneD8vk("1.10.3"))
        assertTrue(TranslationStackResolver.needsStandaloneD8vk("dxvk-async-1.10.3"))
        // 版が読めないときは保守的に「必要」
        assertTrue(TranslationStackResolver.needsStandaloneD8vk(""))
    }

    @Test
    fun `サマリには 4 段と除去件数が出る`() {
        val plan = TranslationStackResolver.resolve(
            inputs(
                isArm64Ec = true,
                dxwrapper = "dxvk-2.7.1",
                env = mapOf("BOX64_LOG" to "1", "VKD3D_FEATURE_LEVEL" to "12_1"),
            ),
        )
        val summary = plan.summary()

        assertTrue(summary.contains("translation-stack"))
        assertTrue(summary.contains("cpu=fexcore"))
        assertTrue(summary.contains("d3d=dxvk-2.7.1"))
        assertTrue(summary.contains("dedup=2"))
        assertTrue(summary.contains("single=true"))
        assertNotNull(plan.layer(TranslationStage.CPU))
        assertNotNull(plan.layer(TranslationStage.WINDOWS))
    }
}
