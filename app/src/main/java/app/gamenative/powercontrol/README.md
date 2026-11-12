# GameNative のパフォーマンス制御アーキテクチャ

## 概要

GameNative のパフォーマンス制御システムは、Android ゲーミング端末向けの CPU／GPU チューニング機能を
提供します。拡張可能なドライバーアーキテクチャにより、PServer ベースの端末（AYN Odin、Retroid
Pocket）や、Samsung Performance SDK を使った Samsung Galaxy 端末など、複数の種類の端末に対応します。
ユーザーは CPU ガバナー、周波数スケーリング、GPU パフォーマンスレベルを調整してゲームの性能を
最適化できます。さらに、PID コントローラーを使って目標 FPS とリアルタイムの使用率から CPU／GPU
設定を動的に調整し、最適な性能を保ちつつリソース消費を最小化する**自動パフォーマンスチューニング
システム**も備えています。

## アーキテクチャ

### 主要コンポーネント

1. **PerformanceDriver**（抽象基底クラス）
   - 場所: `drivers/PerformanceDriver.kt`
   - 端末固有の各パフォーマンスドライバーのインターフェースを定義
   - 周波数の整形など共通機能を提供
   - **抽象メソッド**（実装必須）:
     - `isDriverSupported()` - ドライバーの利用可否の判定
     - `getDisplayUnit()` - 周波数値の表示単位（HZ または INTEGER）を返す
   - **デフォルト実装を持つオープンメソッド**（オーバーライド可能）:
     - `isGovernorSupported()` - CPU ガバナー制御の対応（既定: false）
     - `isGpuSupported()` - GPU 制御の対応（既定: false）
     - `isBusSupported()` - RAM バス制御の対応（既定: false）
     - `isFanSupported()` - ファン制御の対応（既定: false）
     - `start()` - ゲーム開始時にドライバーを初期化（既定: 何もしない）
     - `stop()` - ゲーム終了時にドライバーを後始末（既定: 何もしない）
     - `beginUpdate()` - バッチ更新セッションを開始（既定: 何もしない）
     - `commit()` - 保留中の更新を確定（既定: true を返す）
     - `getDefaultProfile()` - その端末の既定の「バランス」プロファイルを返す
     - CPU: `getCurrentMinCpuValue()`、`getCurrentMaxCpuValue()`、`getCurrentGovernor()`
     - CPU: `setMinCpuValue(value)`、`setMaxCpuValue(value)`、`setGovernor(governor)`
     - CPU: `getAvailableGovernors()`、`getAvailableCpuFrequencies()`
     - GPU: `getCurrentGpuValue()`、`getAvailableGpuFrequencies()`
     - GPU: `getCurrentMinGpuPowerLevel()`、`getCurrentMaxGpuPowerLevel()`、`getNumGpuPowerLevels()`
     - GPU: `setMinGpuPowerLevel(level)`、`setMaxGpuPowerLevel(level)`
     - Bus: `getCurrentMinBusLevel()`、`getCurrentMaxBusLevel()`、`getNumBusLevels()`
     - Bus: `setMinBusLevel(level)`、`setMaxBusLevel(level)`

2. **PServerDriver**（実装）
   - 場所: `drivers/PServerDriver.kt`
   - PServer 対応端末向けの具体的な実装
   - 対応: AYN Odin、Retroid Pocket 端末
   - **binder インターフェースを内蔵** - PServerBinder サービスと直接通信
   - CPU と GPU 制御のためのすべての sysfs パスを管理
   - PServer が使えない場合は直接ファイル読み取りへフォールバック
   - リフレクション経由の Android Binder IPC で ServiceManager にアクセス
   - Adreno GPU（Qualcomm Snapdragon 端末）の GPU 対応
   - **コマンド最適化**: chmod → echo → chmod を 1 つのコマンドに連結して実行を高速化
   - **権限管理**: 書き込み後に sysfs ファイルを 444（読み取り専用）にし、停止時に 644 へ戻す
   - **[GameMode](https://github.com/FeralInteractive/gamemode) に着想を得たポリシーベースの CPU 制御**:
     - 初期化時にシンボリックリンクを解決して CPU ポリシーを検出
     - 同じポリシーを共有する CPU への冗長な書き込みを排除
     - 一般的なマルチコア端末で IPC 呼び出しを 50〜75% 削減
     - ポリシーの検出に失敗した場合は CPU 単位の方式にフォールバック

3. **SamsungPerformanceDriver**（実装）
   - 場所: `drivers/SamsungPerformanceDriver.kt`
   - Samsung Performance SDK を使う具体的な実装
   - 対応: Android 10 以上（2020 年 3 月以降）の Samsung Galaxy 端末
   - 生の周波数ではなくパフォーマンスレベル（1〜4）を使用（レベル 0 = 無効）
   - ソケット経由でシステムデーモンと通信（INTERNET 権限が必要）
   - CustomParams API による CPU と GPU のパフォーマンス制御に対応
   - CPU ガバナー制御はなし（Samsung SDK が内部で管理）
   - **ライフサイクル**: `start()` は何もしない（制御は各 setter が開始する）。`stop()` は
     `performanceManager.stop()` を呼び、すべての有効な制御を停止する

4. **PowerManager**（ファサード）
   - 場所: `PowerManager.kt`
   - UI コンポーネント向けの高水準 API
   - すべての操作を有効な PerformanceDriver に委譲
   - データクラスを提供: `CpuInfo`、`GpuInfo`
   - CPU ガバナー、周波数、GPU 制御のメソッドを公開
   - **プロファイル管理**: `currentProfile` を保持し、ドライバーの状態と同期
   - **プロファイルの永続化**: JSON シリアライズで `PrefManager` 経由で保存／復元
   - **自動同期**: すべての setter がドライバーと `currentProfile` の両方を更新
   - **自動チューニングの管理**:
     - 目標 FPS（XServer のフレームレートリミッター由来）を保持
     - 現在の FPS、CPU 使用率、GPU 使用率（Performance HUD 由来）を保持
     - `PerformanceAutoTuner` のライフサイクル（開始／停止）を管理
     - 自動チューナーが CPU／GPU 設定を調整するためのコールバックを提供
   - 既存の UI コードとの後方互換性を維持

5. **PowerProfile**（データクラス）
   - 場所: `PowerProfile.kt`
   - パフォーマンス設定の一式を表すシリアライズ可能なデータクラス
   - フィールド（すべて可変の `var`）:
     - `enableAutoTuning: Boolean` - 自動パフォーマンスチューニングを有効化（既定: false）
     - `name: String` - プロファイル名（例: 「Balanced」「Performance」「Custom」）
     - `governor: CpuGovernor` - CPU ガバナーの enum
     - `minCpuFreq: Long` - 最小 CPU 周波数／レベル
     - `maxCpuFreq: Long` - 最大 CPU 周波数／レベル
     - `minGpuPowerLevel: Int` - 最小 GPU パワーレベル（既定: 0）
     - `maxGpuPowerLevel: Int` - 最大 GPU パワーレベル（既定: 0）
   - プロファイルの永続化、UI 状態、既定プロファイルに使用

6. **PowerProfiles**（オブジェクト）
   - 場所: `PowerProfile.kt`
   - `getDefaultProfiles()` ファクトリメソッドを提供
   - 端末固有のプリセットプロファイルを生成:
     - **Power Save**: 低周波数（CPU 25%、GPU 25%）、powersave ガバナー
     - **Balanced**: 中間周波数（CPU 50%、GPU 50〜100%）、schedutil／conservative／interactive ガバナー
     - **Performance**: 高周波数（CPU 75%、GPU 75〜100%）、performance ガバナー
     - **On Demand**: 全範囲（CPU／GPU 0〜100%）、ondemand ガバナー
     - **WALT**: 全範囲（CPU／GPU 0〜100%）、walt ガバナー
   - 利用可能な周波数から周波数の段階を動的に計算
   - GPU パワーレベルは最大 GPU パワーレベルの割合として計算

7. **PerformanceAutoTuner**（自動チューニング）
   - 場所: `autotuning/PerformanceAutoTuner.kt`
   - PID コントローラーを使う自動パフォーマンスチューナー
   - 目標／現在の FPS に基づいて CPU 周波数、GPU パワーレベル、対応していれば RAM バスレベルを
     動的に調整します。CPU／GPU の使用率も CPU／GPU の調整に反映されます:
     - 目標 FPS（XServer のフレームレートリミッター由来）
     - 現在の FPS（Performance HUD 由来）
     - CPU 使用率（%）
     - GPU 使用率（%）
   - **適応的なパフォーマンススケーリング**:
     - 目標 FPS を達成していて使用率が低い場合は性能を下げる
     - 目標 FPS に届かない、または使用率が高い場合は性能を上げる
     - PID コントローラーで滑らかかつ応答性の高い調整を行う
   - **設定可能なしきい値**:
     - FPS 誤差のしきい値: 2.0 FPS（小）、5.0 FPS（大）
     - 使用率の下限しきい値: 70%（性能を下げる）
     - 使用率の上限しきい値: 85%（性能を上げる）
     - パフォーマンス範囲: 20〜100%
   - **チューニング周期**: バックグラウンドスレッドで 2 秒ごとに実行
   - **統合**: `PowerProfile.enableAutoTuning` フラグで有効化

8. **PidController**（制御理論）
   - 場所: `autotuning/PidController.kt`
   - 滑らかなパフォーマンス調整のための比例・積分・微分（PID）コントローラー
   - **チューニングパラメータ**:
     - Kp（比例ゲイン）: 0.5 - FPS 誤差への即時応答
     - Ki（積分ゲイン）: 0.2 - 時間経過とともに定常偏差を解消
     - Kd（微分ゲイン）: 0.1 - 振動を抑えて安定させる
   - **ワインドアップ防止**: 積分項を ±50.0 に制限
   - **出力のクランプ**: 出力を有効範囲（-100.0〜+100.0）に制限
   - **状態管理**: 積分値、前回の誤差、時間差を保持
   - CPU 用と GPU 用に別々のコントローラーを持つ

## 対応機能

### 現状（PServerDriver）

**CPU 制御:**
- ✅ CPU ガバナー制御
- ✅ CPU 周波数スケーリング（最小／最大）
- ✅ 複数ガバナーの対応（schedutil、performance、powersave など）
- ✅ sysfs ファイルの権限管理（書き込み後 chmod 444、停止時に 644 へ復元）
- ✅ **CPU ピニング／プロセスアフィニティ制御**:
  - ~~アプリプロセスの高効率コアへの自動ピニング~~（ANR が発生する可能性があるため削除）
  - PulseAudio を専用のパフォーマンスコアへ自動ピニング
  - Wine ゲームプロセスの再試行ロジック付きピニング
  - Wine のインフラ（wineserver、winhandler、services.exe）のピニング
  - クラスタベースのコア選択（EFFICIENCY、PERFORMANCE、PRIME）
  - `/proc/cmdline` の走査による Wine 対応の PID 検出

**GPU 制御（Adreno）:**
- ✅ GPU パワーレベル制御（最小／最大パワーレベル）
- ✅ パワーレベル範囲の検出（`num_pwrlevels`）
- ✅ 利用可能な GPU 周波数の列挙（読み取り専用）
- ✅ 現在の GPU 周波数の監視（読み取り専用）
- ✅ sysfs ファイルの権限管理（書き込み後 chmod 444、停止時に 644 へ復元）
- ❌ GPU 周波数の直接設定（ハードウェア非対応）

**自動チューニング:**
- ✅ PID コントローラーによる自動パフォーマンスチューニング
- ✅ FPS と CPU 使用率に基づく CPU 周波数の動的調整
- ✅ FPS と GPU 使用率に基づく GPU パワーレベルの動的調整
- ✅ 適応的なパフォーマンススケーリング（過剰なら下げ、不足なら上げる）
- ✅ FPS 誤差とリソース使用率のしきい値を設定可能
- ✅ 2 秒周期のバックグラウンドチューニングスレッド
- ✅ 目標 FPS のための XServer フレームレートリミッターとの統合
- ✅ 現在の指標（FPS、CPU／GPU 使用率）のための Performance HUD との統合
- ✅ ワインドアップ防止付きの CPU 用／GPU 用の独立した PID コントローラー

**ライフサイクル:**
- ✅ `start(containerDir)` - コンテナ固有ファイルから保存済みプロファイルを復元（無ければ既定の
  「バランス」プロファイルを適用）
  - **アプリプロセスを高効率コアへ自動ピニング**
  - **PulseAudio を最初のパフォーマンスコアへ自動ピニング**
  - **プロファイルで有効なら自動チューニングを開始**
- ✅ `stop()` - **重要な性能の復元処理**:
  1. **自動チューニングスレッドを停止し、PID コントローラーをリセット**
  2. CPU 周波数を全範囲（最小〜利用可能な最大）へ戻す
  3. GPU パワーレベルを全範囲（0〜最大）へ戻す
  4. CPU ガバナーを最初に利用可能なガバナーへ戻す
  5. 変更したすべての sysfs ファイルを 644 権限へ戻す
  6. **アプリプロセスの CPU アフィニティを全コアへ戻す**
  - バックグラウンドスレッドで非同期に実行
  - **Power Save モードから抜ける際の端末の重さを防ぐ**

### CPU ピニング（プロセスアフィニティ制御）

**概要:**
CPU ピニングは特定のプロセスを専用の CPU コアに割り当て、次の点で性能を最適化します:
- スレッドマイグレーションのオーバーヘッドを削減（50〜75% 削減）
- キャッシュの局所性を改善（キャッシュ無効化の減少）
- 重要なプロセス間の干渉を防止
- prime コアを最大周波数までブーストさせる

**自動ピニング（ドライバーレベル）:**

PServerDriver は開始／停止時に CPU ピニングを自動で行います:

*`start()` 時:*
- **アプリプロセス** → EFFICIENCY コアへピニング（一般的な端末では CPU 0〜2）
  - ゲーム用プロセスにパフォーマンスコアを空ける
  - UI／オーバーレイに高周波コアは不要
  - 消費電力を削減
- **PulseAudio** → 最初の PERFORMANCE コアへピニング（一般的な端末では CPU 3）
  - 低遅延オーディオ用の専用コア
  - 音切れ／アンダーランを防止
  - ゲームとのキャッシュ競合なし

*`stop()` 時:*
- **アプリプロセス** → 利用可能なすべてのコアへ戻す
  - Android スケジューラーの既定動作に戻す
  - ゲーム以外のときの UI 応答性を確保

**手動ピニング（ゲームと Wine インフラ）:**

ゲームプロセスと Wine インフラは PowerManager のメソッドでピニングします:

*ゲームプロセスのピニング:*
```kotlin
PowerManager.pinGameWithRetry(
    processName = "DaveTheDiver.exe",
    maxRetries = 10,
    retryDelayMs = 1000
)
```
- Wine 対応の PID 検出を使用（`.exe` プロセスを `/proc/cmdline` から探す）
- 最大 10 回、1 秒間隔で再試行
- PERFORMANCE + PRIME コアへピニング（一般的な端末では CPU 3〜7）
- 成功／失敗を試行回数とともにログ出力

*Wine インフラのピニング:*
```kotlin
PowerManager.pinWineInfrastructure()
```
- **wineserver** → PERFORMANCE コア（CPU 3〜6）- Wine の IPC に重要
- **winhandler.exe** → PERFORMANCE + PRIME コア（CPU 4〜7）- ウィンドウ管理
- **services.exe** → 最初の 2 つの PERFORMANCE コア（CPU 3〜4）- Windows サービス
- Wine の初期化完了を 2 秒待つ
- 各プロセスのピニング結果をログ出力

**クラスタベースのコア選択:**

PServerDriver は端末に依存しないピニングのためにクラスタベースのコア選択を使います:

```kotlin
// Get cores by cluster type
val effCores = driver.getCpuCoresByCluster(CpuCluster.EFFICIENCY)    // CPUs 0-2
val perfCores = driver.getCpuCoresByCluster(CpuCluster.PERFORMANCE)  // CPUs 3-6
val primeCores = driver.getCpuCoresByCluster(CpuCluster.PRIME)       // CPU 7

// Pin to specific cluster
driver.setCpuAffinityByCores(pid, perfCores)
```

**CPU クラスタの種類:**
- `EFFICIENCY` - 最低周波数のコア（省電力）
- `PERFORMANCE` - 中〜高周波数のコア（バランス）
- `PRIME` - 最高周波数のコア（最高性能）

**CPU 割り当ての全体像（Snapdragon 8 Gen 2 の例）:**

```
CPU 0-2 (Efficiency @ 2.0 GHz):  GameNative app, Android system
CPU 3 (Performance @ 2.8 GHz):    PulseAudio, wineserver, services.exe, game
CPU 4-6 (Performance @ 2.8 GHz): wineserver, services.exe, game, winhandler
CPU 7 (Prime @ 3.2 GHz):         game, winhandler (BOOST!)
```

**性能への影響:**

*ピニング前:*
- FPS: 40〜45（不安定）
- prime コアの周波数: 2.476 GHz（使い切れていない）
- フレームペーシング: 不安定（カクつき）
- 音声: 時々ノイズ

*ピニング後:*
- FPS: 58〜60（安定）
- prime コアの周波数: 3.0〜3.2 GHz（ブースト）
- フレームペーシング: 滑らかで安定
- 音声: クリアでノイズなし
- キャッシュ効率: 改善（競合なし）

**Wine 対応の PID 検出:**

Wine ゲームでは通常の `pidof` が使えません。理由は:
- Wine のプロセスは Wine の実行ファイル名を持つ Linux プロセスとして見える
- 実際のゲームの実行ファイルは `/proc/<pid>/cmdline` にある

PServerDriver は `/proc` を走査する `findWineProcessPid()` を提供します:
```kotlin
driver.findWineProcessPid("DaveTheDiver.exe")  // Returns PID or null
```

**統合例:**

```kotlin
// In XServerScreen.kt after game environment setup
PowerManager.start(container.rootDir)  // Auto-pins app + PulseAudio, loads per-container profile

// Pin game process
val executableName = container.executablePath
    .substringAfterLast('/')
    .substringAfterLast('\\')
    .takeIf { it.isNotEmpty() }
    ?.let { name ->
        val baseName = name.substringBefore(".exe", name)
        PowerManager.pinGameWithRetry(
            processName = "$baseName.exe",
            maxRetries = 10,
            retryDelayMs = 1000
        )
    }

// Pin Wine infrastructure
PowerManager.pinWineInfrastructure()
```

**想定されるログ:**
```
PServerDriver: Pinned app process (PID: 12345) to efficiency CPUs 0, 1, 2
PowerManager: Pinned PulseAudio (PID: 10642) to CPU 3
PowerManager: Pinned DaveTheDiver.exe (PID: 11540) to CPUs 3, 4, 5, 6, 7 after 1 attempts
PowerManager: Pinned wineserver (PID: 11309) to CPUs 3, 4, 5, 6
PowerManager: Pinned winhandler.exe (PID: 11536) to CPUs 3, 4, 5, 6, 7
PowerManager: Pinned services.exe (PID: 11342) to CPUs 3, 4
```

**手動テスト（ADB）:**
```bash
# Get game PID
adb shell ps -A | grep -i dave

# Pin to CPUs 4-7
adb shell su -c "taskset -p 0xf0 <pid>"

# Verify affinity
adb shell su -c "taskset -p <pid>"
```

### GameMode に着想を得た改善

**概要:**
PServerDriver には、オンデマンドでシステム性能を最適化する Linux デーモン
[Feral Interactive の GameMode](https://github.com/FeralInteractive/gamemode) に着想を得た最適化が
取り込まれています。これらの改善により、一般的なマルチコア端末で IPC のオーバーヘッドが
**50〜75%** 削減されます。

**ポリシーベースの CPU 制御:**

*検出フェーズ（ドライバー開始時）:*
- `File.canonicalPath` を使って各 CPU の `scaling_governor` ファイルのシンボリックリンクを解決
- 実際のポリシーディレクトリ（例: `/sys/devices/system/cpu/cpufreq/policy0`）ごとに CPU をグループ化
- 一意なポリシーごとに関連する CPU コアをすべて含む `CpuPolicy` オブジェクトを作成
- 以降の操作のためにキャッシュ（ドライバーのライフサイクル中に 1 度だけ検出）

*利点:*
- ポリシーを共有する端末（最近の SoC では一般的）で **IPC 呼び出しが 50〜75% 削減**
- 例: 単一ポリシーの 8 コア端末 → 24 回ではなく 3 回の IPC 呼び出し（87.5% 削減）
- 同じ cpufreq ポリシーを共有する CPU への冗長な書き込みを排除
- ポリシーの同時変更による競合状態に対してより堅牢

*フォールバックの挙動:*
- ポリシーの検出に失敗した場合は CPU 単位の方式（従来の挙動）にフォールバック
- あらゆる端末構成との互換性を確保
- デバッグ用に検出したポリシーの詳細をログ出力

**CPU ポリシーのデータ構造:**

```kotlin
private data class CpuPolicy(
    val policyId: Int,
    val governorPath: String,
    val minFreqPath: String,
    val maxFreqPath: String,
    val cpuCores: List<Int>,
    val maxFrequency: Long  // Maximum frequency for this policy
)
```

**sysfs の検証:**

ドライバーは初期化時に CPU 周波数スケーリングの対応を検証します:

*検証対象:*
- `/sys/devices/system/cpu` - CPU のベースディレクトリ
- `/sys/devices/system/cpu/cpufreq` - CPUFreq ディレクトリ
- `/sys/devices/system/cpu/cpufreq/policy0` - policy0 ディレクトリ
- `/sys/devices/system/cpu/cpufreq/policy0/scaling_governor` - ガバナーファイル

*利点:*
- cpufreq 非対応の早期検出
- トラブルシューティングに役立つエラーメッセージ
- 実行中の分かりにくいエラーを防止

**CPU クラスタの識別:**

ドライバーは周波数の能力に基づいて CPU クラスタを自動的に識別します:

```kotlin
enum class CpuCluster {
    EFFICIENCY,    // Lowest frequency cores
    PERFORMANCE,   // Mid-high frequency cores
    PRIME          // Highest frequency core(s)
}
```

*検出の流れ:*
- ポリシーを最大周波数でソート
- ポリシー数と周波数帯に基づいてクラスタ種別を割り当て
- デュアルクラスタ（big.LITTLE）とトリクラスタ（efficiency + performance + prime）の構成に対応
- CPU ピニングのためのクラスタ→コアの対応を提供

**ポリシーごとの周波数上限:**

CPU 周波数を設定するとき、ドライバーは各ポリシーの最大周波数を尊重します:

```kotlin
// In setMinCpuValue() and setMaxCpuValue()
val cappedValue = min(value, policy.maxFrequency)
writeSysfsFile(policy.minFreqPath, cappedValue.toString())
```

*利点:*
- 設定不可能な周波数を設定しようとしない
- 各コアがハードウェアの能力の範囲内で動作することを保証
- デバッグ用に上限適用の操作をログ出力

**内部状態の追跡:**

ドライバーは sysfs から読むのではなく、最後に要求した値を保持します:

```kotlin
private var currentMinCpuFreq: Long = 0L
private var currentMaxCpuFreq: Long = 0L
private var currentGovernor: String = ""
```

*利点:*
- `getCurrentMinCpuValue()`、`getCurrentMaxCpuValue()`、`getCurrentGovernor()` が意図した値を返す
- 実際に要求した内容と一貫する
- ポリシーごとに値が異なる場合の混乱を回避

**網羅的な周波数の検出:**

`getAvailableCpuFrequencies()` はすべての CPU ポリシーから周波数を集めるようになりました:

```kotlin
for (policy in cpuPolicies) {
    val freqs = readSysfsFile("$policyDir/scaling_available_frequencies")
    allFrequencies.addAll(freqs)
}
```

*利点:*
- すべての CPU クラスタの周波数を含む
- UI に完全な周波数範囲を提供
- プロファイルの周波数選択が正確になる

### 現状（SamsungPerformanceDriver）

**CPU 制御:**
- ✅ CPU パフォーマンスレベルの制御（1〜4 のスケール、0 = 無効）
- ✅ CPU パフォーマンスレベルの最小／最大
- ✅ タイムアウトの自動管理（0 = 無限）
- ❌ CPU ガバナー制御（Samsung SDK が管理）
- ❌ 生の周波数制御（パフォーマンスレベルを使用）

**GPU 制御:**
- ✅ GPU パフォーマンスレベルの制御（1〜4 のスケール、0 = 無効）
- ✅ GPU パフォーマンスレベルの最小／最大
- ✅ リソースの自動管理
- ❌ GPU 周波数の直接設定（非対応）

**ライフサイクル:**
- ✅ `start(containerDir)` - コンテナ固有ファイルから保存済みプロファイルを復元（無ければ既定の
  「バランス」プロファイルを適用）
- ✅ `stop()` - `performanceManager.stop()` を呼び、すべての有効なパフォーマンス制御を停止する。
  PowerManager はこのメソッドを呼ぶ前に現在のプロファイルを保存する。

### 今後の候補
- ⏳ ファン回転数の制御
- ⏳ 端末固有ドライバーの追加
- ⏳ Mali GPU の対応（Samsung 以外）

## 主要な設計判断

### 汎用的な抽象化
- **引数の命名**: メソッドは `frequency: Long` ではなく汎用的な `value: Long` を使う
- **汎用的なコメント**: 基底クラスでは「KHz 単位の周波数」ではなく「CPU パフォーマンス値」と書く
- **実装固有のドキュメント**: PServerDriver は自身のドキュメントで「KHz 単位の周波数」と明記する
- これにより将来のドライバーが異なる単位（百分率、パフォーマンスレベルなど）を使える

### 表示単位

`PerformanceDriver.DisplayUnit` enum は周波数値の表示方法を定義します:
- `HZ`: 生のヘルツ値（例: 2400000000 Hz）
- `INTEGER`: 人間が読みやすい形式（例: 2.4 GHz）

現状:
- PServerDriver は `INTEGER` 形式を使用
- SamsungPerformanceDriver は `INTEGER` 形式を使用（パフォーマンスレベル用）

### GPU パワーレベルの意味

すべてのドライバーは、**値が大きいほど高性能**という**正規化されたパワーレベル
インターフェース**を公開します。

**統一された API の意味（すべてのドライバー）:**
- レベル 0 = 最低性能
- レベル N = 最高性能
- パワーレベルが高いほど高性能
- これは `getCurrentMinGpuPowerLevel()` と `getCurrentMaxGpuPowerLevel()` の両方に当てはまる

**ドライバー固有の内部処理:**

*PServerDriver（Adreno GPU）:*
- Adreno の sysfs は逆順のインデックスを使う: `max_pwrlevel = 0`（最速）、インデックスが大きいほど低速
- PServerDriver は UI の意味と sysfs の意味を**内部的に変換**する
- 変換: `sysfs_level = numGpuPowerLevels - 1 - ui_level`
- UI 側のコードはこの逆転を意識する必要がない

*SamsungPerformanceDriver:*
- Samsung SDK は自然な順序を使う: レベル 1〜4 で大きいほど高性能
- 変換は不要で、値をそのまま渡す

**GPU パワーレベルの制御:**
- `setMinGpuPowerLevel(level)` - `min_pwrlevel` 経由で性能の下限を設定
- `setMaxGpuPowerLevel(level)` - `max_pwrlevel` 経由で性能の上限を設定
- GPU 周波数は読み取り専用で、パワーレベルの制約内で GPU ガバナーが管理する

### プロファイルの永続化

PowerManager はアプリのセッションをまたいでパフォーマンスプロファイルを自動で保存／復元します:

**永続化の仕組み:**
- プロファイルは `kotlinx.serialization` を使って JSON にシリアライズ
- コンテナごとに `<container_dir>/.config/.power-profile` に保存
- 各コンテナが独立した独自のパワープロファイルを持つ
- プロファイルのすべてのフィールドを含む: 名前、ガバナー、CPU 周波数、GPU パワーレベル、
  バスレベル、自動チューニング設定

**ライフサイクルへの統合:**
1. **`start(containerDir)` 時**:
   - コンテナごとのプロファイル保存先としてコンテナディレクトリのパスを受け取る
   - `<container_dir>/.config/.power-profile` から保存済みプロファイルの復元を試みる
   - 保存済みプロファイルが無ければ、`driver.getDefaultProfile()` の既定の「バランス」プロファイルを適用
   - ドライバーのメソッド経由でプロファイルをハードウェアに適用
   - `.config` ディレクトリはプロファイル保存時に自動で作成される

2. **`stop()` 時**:
   - `currentProfile` をコンテナ固有のファイルへ保存（null でない場合）
   - コンテナごとに、ユーザーの最後の設定が次のセッションのために保持される

3. **実行中**:
   - すべての setter（`setGovernor`、`setMinCpuValue` など）が `currentProfile` を自動で更新
   - 個別の設定を変更するとプロファイル名は「Custom」になる
   - プリセットプロファイルを適用した場合はプロファイル名が保たれる

**プロファイルの同期:**
- `PowerManager.currentProfile` は常に実際のドライバー状態を反映する
- setter はドライバーと `currentProfile` の両方を原子的に更新する
- ドライバーの操作が成功した場合のみプロファイルを更新する
- 永続化データがハードウェアの状態と一致することを保証

**UI との統合:**
- UI はプロファイル名を `PowerManager.currentProfile?.name` と突き合わせる
- どのプリセットにも一致しない設定のときはドロップダウンに「Custom」を表示
- プロファイルの選択は UI 状態と PowerManager の両方を即時に更新

## ドライバーの選択

PowerManager は初期化時に適切なドライバーを自動で選択します:

1. **Samsung Performance SDK** - Samsung 端末の場合に最初に確認
2. **PServer** - AYN Odin、Retroid Pocket 端末の場合に確認
3. **フォールバック** - NoOpPerformanceDriver を使用（機能なし）

選択は `PowerManager.initialize(context)` で行われ、アプリの起動時に呼ぶ必要があります。

## ドライバーのライフサイクル

ドライバーはゲームのライフサイクルに沿って動作し、プロファイルを自動で永続化します:

1. **ゲーム環境のセットアップ**（`XServerScreen.kt`）
   - `PluviaApp.xEnvironment` の初期化後
   - `PowerManager.start(container.rootDir)` をコンテナディレクトリ付きで呼び出す
   - **プロファイルの復元**:
     - `<container_dir>/.config/.power-profile` から保存済みプロファイルの読み込みを試みる
     - 保存済みプロファイルが無ければ、`driver.getDefaultProfile()` の既定の「バランス」プロファイルを適用
     - ドライバーのメソッド経由でプロファイル設定をハードウェアに適用
   - ドライバー固有の初期化を実行

2. **ゲーム実行中**
   - ユーザーは UI からパフォーマンス設定を調整できる
   - 設定の変更ごとに該当するドライバーのメソッドを呼ぶ
   - **プロファイルの自動同期**:
     - すべての setter がドライバーと `PowerManager.currentProfile` の両方を更新
     - 個別の設定を変更するとプロファイル名が「Custom」になる
     - プロファイル全体を適用した場合はプリセット名が保たれる
   - PServerDriver: sysfs へ書き込み、ファイルを 444（読み取り専用）にする
   - SamsungPerformanceDriver: 新しい設定で `performanceManager.start(params)` を呼ぶ

3. **ゲーム環境の終了**（`PluviaApp.shutdownEnvironment()`）
   - `PowerManager.stop()` を呼ぶ
   - **プロファイルの永続化**:
     - `currentProfile` をコンテナ固有のファイルへ保存し、次のセッションに引き継ぐ
   - **ハードウェアの復元**:
     - PServerDriver:
       1. CPU 周波数を全範囲へ戻す（Power Save による重さを防止）
       2. GPU パワーレベルを全範囲へ戻す
       3. CPU ガバナーを最初に利用可能なガバナーへ戻す
       4. 変更したすべての sysfs ファイルを 644 権限へ戻す
     - SamsungPerformanceDriver: `performanceManager.stop()` を呼び、すべてのパフォーマンス制御を停止

## 新しいドライバーの追加

新しい端末への対応を追加するには:

1. `drivers/` フォルダに `PerformanceDriver` を継承した新しいドライバークラスを作成
2. 端末の能力に応じてすべての抽象メソッドを実装:
   - **必須**: `getDefaultProfile()` - その端末の「バランス」プロファイルを返す
     - 中程度の性能設定（CPU 50%、GPU 50%）を返すべき
     - プロファイル名には `PerformancePreset.BALANCED.displayName` を使う
     - 端末の利用可能な周波数／レベルに基づいて適切な値を計算する
3. 必要な import を追加:
   ```kotlin
   import app.gamenative.powercontrol.PowerProfile
   import app.gamenative.powercontrol.profiles.CpuGovernor
   import app.gamenative.powercontrol.profiles.PerformancePreset
   ```
4. `PowerManager.initialize()` を更新して、選択ロジックに新しいドライバーを含める:

```kotlin
fun initialize(context: Context) {
    driver = when {
        NewDriver(context).isDriverSupported() -> NewDriver(context)
        SamsungPerformanceDriver(context).isDriverSupported() -> SamsungPerformanceDriver(context)
        PServerDriver().isDriverSupported() -> PServerDriver()
        else -> NoOpPerformanceDriver() // Fallback
    }
}
```

## 実装の詳細

### SamsungPerformanceDriver

**Samsung Performance SDK との統合:**
- `com.samsung.sdk.sperf` パッケージを使用
- `app/src/main/lib/` に `perfsdk-v1.0.0.jar` が必要
- AndroidManifest.xml に `INTERNET` 権限が必要
- `SPerf.initialize(context)` で SDK を初期化
- 制御用に `PerformanceManager` インスタンスを作成

**パフォーマンスレベル:**
Samsung SDK は生の周波数ではなくパフォーマンスレベル（1〜4）を使います:
- レベル 0: 無効（システム既定。ドライバーでは使わない）
- レベル 1: 低性能
- レベル 2: 中性能
- レベル 3: 高性能
- レベル 4: 最高性能

**CustomParams API:**
```kotlin
val params = CustomParams()
params.add(CustomParams.TYPE_CPU_MIN, level, timeout)
performanceManager.start(params)
```

利用可能なパラメータ種別:
- `TYPE_CPU_MIN` - CPU パフォーマンスレベルの下限
- `TYPE_CPU_MAX` - CPU パフォーマンスレベルの上限
- `TYPE_GPU_MIN` - GPU パフォーマンスレベルの下限
- `TYPE_GPU_MAX` - GPU パフォーマンスレベルの上限

**タイムアウトの管理:**
- タイムアウトはミリ秒単位（0 = 無限）
- タイムアウトに達するとパフォーマンス制御は自動停止
- ゲーム終了時に `performanceManager.stop()` が自動で呼ばれる
  （`SamsungPerformanceDriver.stop()` 経由）

**開始／停止の挙動:**
- `start()`: 何もしない - パフォーマンス制御は各 setter が個別に開始する
  - 各 setter（`setMinCpuValue`、`setMaxCpuValue` など）が `performanceManager.start(params)` を呼ぶ
  - これによりパラメータ単位の細かい制御が可能
- `stop()`: `performanceManager.stop()` を呼び、**すべての**有効なパフォーマンス制御を停止する
  - ゲーム環境の終了時に自動で呼ばれる
  - すべてのパフォーマンスロックを解放する

**プリセットの対応:**
Samsung SDK はプリセットのパフォーマンスプロファイルも提供します:
- `PRESET_TYPE_CPU` - CPU 重視のシナリオ
- `PRESET_TYPE_GPU` - GPU 重視のシナリオ
- `PRESET_TYPE_BUS` - I/O やメモリアクセス重視のシナリオ

現在の SamsungPerformanceDriver では使っていません（細かい制御には CustomParams を使用）。

### PServerDriver

**ポリシーベースの CPU 制御（GameMode に着想）:**

ドライバーは
[Feral Interactive の GameMode](https://github.com/FeralInteractive/gamemode) に着想を得た、
最適化されたポリシーベースの方式で CPU を制御します:

*検出フェーズ（ドライバー開始時）:*
- `start()` が呼ばれたときに実行（遅延初期化）
- `File.canonicalPath` を使って各 CPU の `scaling_governor` ファイルのシンボリックリンクを解決
- 実際のポリシーディレクトリ（例: `/sys/devices/system/cpu/cpufreq/policy0`）ごとに CPU をグループ化
- 一意なポリシーごとに関連する CPU コアをすべて含む `CpuPolicy` オブジェクトを作成
- 以降の操作のためにキャッシュ（ドライバーのライフサイクル中に 1 度だけ検出）

*利点:*
- ポリシーを共有する端末（最近の SoC では一般的）で **IPC 呼び出しが 50〜75% 削減**
- 例: 単一ポリシーの 8 コア端末 → 24 回ではなく 3 回の IPC 呼び出し（87.5% 削減）
- 同じ cpufreq ポリシーを共有する CPU への冗長な書き込みを排除
- ポリシーの同時変更による競合状態に対してより堅牢

*フォールバックの挙動:*
- ポリシーの検出に失敗した場合は CPU 単位の方式（従来の挙動）にフォールバック
- あらゆる端末構成との互換性を確保
- デバッグ用に検出したポリシーの詳細をログ出力

*検証:*
- 初期化時に CPU 周波数スケーリングの対応を確認
- 主要な sysfs パス（`/sys/devices/system/cpu/cpufreq/policy0` など）の存在を検証
- カーネル／BIOS で cpufreq が無効な場合に役立つ警告を出す

**Binder サービスとの通信:**
- リフレクション経由の Android Binder IPC で `ServiceManager` にアクセス
- 対応端末で `PServerBinder` サービスに接続
- binder トランザクションで root コマンドを実行
- Parcel ベースのデータのエンコード／デコード

**sysfs パス:**
すべての sysfs パスは `PServerDriver` にカプセル化されています:

*CPU:*
- ベース: `/sys/devices/system/cpu`
- ポリシー: `/sys/devices/system/cpu/cpufreq/policy0`
- CPU 単位: `/sys/devices/system/cpu/cpu{N}/cpufreq/`

*GPU（Adreno）:*
- ベース: `/sys/class/kgsl/kgsl-3d0`
- Devfreq: `/sys/class/kgsl/kgsl-3d0/devfreq`
- 周波数: `/sys/class/kgsl/kgsl-3d0/devfreq/cur_freq`
- 利用可能な周波数: `/sys/class/kgsl/kgsl-3d0/devfreq/available_frequencies`
- パワーレベル: `/sys/class/kgsl/kgsl-3d0/min_pwrlevel`、`max_pwrlevel`
- パワーレベル数: `/sys/class/kgsl/kgsl-3d0/num_pwrlevels`

**フォールバックの仕組み:**
1. PServer binder サービスを試す（第一候補）
2. アクセス可能なら直接ファイル読み取りへフォールバック
3. 失敗してもログを残して穏やかに終わる

**コマンドの最適化:**
実行を速くするため、コマンドはセミコロンで連結されます:
```kotlin
// Read operation
executeAsRoot("cat '/sys/devices/system/cpu/cpufreq/policy0/scaling_governor'")

// Write operation (single command instead of 3 separate calls)
executeAsRoot("chmod 644 '$path'; echo '$value' > '$path'; chmod 444 '$path'")

// Batch permission restoration on stop
executeAsRoot("chmod 644 '$path1'; chmod 644 '$path2'; chmod 644 '$path3'")
```

**権限の管理:**
- 書き込み操作は連結したコマンドを使う: `chmod 644 → echo → chmod 444`（IPC 呼び出しは 1 回）
- 書き込み後はファイルを 444（読み取り専用）にして誤った変更を防ぐ
- `stop()` では、変更したすべてのファイルを連結した chmod コマンドで 644 権限へ戻す
- `setGovernor()` が呼ばれた後は、ガバナーファイルが復元リストに自動で追加される
- 変更したファイルは `modifiedSysfsFiles` セットで追跡し、確実に後始末する

### 自動チューニングの実装

**概要:**
自動チューニングシステムは PID（比例・積分・微分）コントローラーを使い、リアルタイムの性能指標に
基づいて CPU 周波数と GPU パワーレベルを自動調整します。目標 FPS を維持しながらリソース消費を
最適化します。

**アーキテクチャ:**

*PerformanceAutoTuner:*
- 2 つの独立した PID コントローラー（CPU 用、GPU 用）を管理
- 2 秒周期のチューニングをバックグラウンドスレッドで実行
- 監視項目: 目標 FPS、現在の FPS、CPU 使用率、GPU 使用率
- パフォーマンスレベルを 20〜100% の範囲で調整
- パフォーマンスの百分率を実際のハードウェアの周波数／レベルへ対応付け

*PidController:*
- 古典的な PID 制御アルゴリズムを実装
- **比例項（Kp=0.5）**: FPS 誤差への即時応答
- **積分項（Ki=0.2）**: 時間経過とともに定常偏差を解消
- **微分項（Kd=0.1）**: 振動を抑えて安定させる
- **ワインドアップ防止**: 積分項を ±50.0 に制限
- **出力のクランプ**: 調整量を ±100.0 の範囲に制限

**チューニングのロジック:**

*性能を下げる（保守的）:*
```
if (fpsError < 2.0 && usage < 70% && performance > 25%) {
    performance -= 2.0%  // Gradual reduction
    reset PID controller
}
```

*性能を上げる（積極的）:*
```
if (fpsError > 5.0 || usage > 85%) {
    adjustment = PID.calculate(targetFps, currentFps)
    performance += adjustment * 0.3  // Damped increase
}
```

*性能を維持する（安定）:*
```
else {
    reset PID controller  // Clear integral/derivative state
}
```

**統合ポイント:**

1. **XServerScreen** → フレームレートリミッターから `PowerManager.targetFps` を設定
2. **PerformanceHudView** → `PowerManager.currentFps`、`currentCpuUsage`、`currentGpuUsage` を更新
3. **PowerManager** → コールバック付きで `PerformanceAutoTuner` を生成:
   - `onCpuFrequencyChange(freq)` → `setMinCpuValue()` と `setMaxCpuValue()` を呼ぶ
   - `onGpuLevelChange(level)` → `setMinGpuPowerLevel()` と `setMaxGpuPowerLevel()` を呼ぶ
4. **PowerProfile** → `enableAutoTuning` フラグが自動チューナーのライフサイクルを制御

**UI の挙動:**
- 自動チューニングが有効なときは手動の CPU／GPU 制御を非表示にする
- 自動チューニングの切り替えは、ドライバーが CPU と GPU の両方の制御に対応している場合のみ表示
- 自動チューニングを切り替えるとプロファイル名が「Custom」になる

**性能の特性:**

*起動フェーズ（0〜10 秒）:*
- PID コントローラーはゼロの状態で初期化
- 性能は 50% の基準値から開始
- 積分項が蓄積されるにつれて急速に調整

*定常状態（10 秒以降）:*
- 目標 FPS の周辺での小さな振動（±2 FPS）
- 積分項が定常偏差を補償
- 微分項がオーバーシュートを防ぐ

*負荷の変化:*
- シーンの複雑さが増す → FPS が下がる → PID が性能を上げる
- シーンの複雑さが減る → FPS は安定し使用率が下がる → 性能を徐々に下げる
- FPS が急に跳ね上がる → 微分項が応答を抑える

**チューニングセッションの例（簡略化した説明用）:**
```
[0s]  Target: 60 FPS, Current: 45 FPS, CPU usage: 50%, GPU usage: 50%
      → Large FPS error (15.0 > 5.0 threshold)
      → PID calculates adjustment, applies with decay factor (0.3)
      → CPU perf: 50% → 52%, GPU perf: 50% → 52%

[2s]  Target: 60 FPS, Current: 54 FPS, CPU usage: 88%, GPU usage: 87%
      → FPS error = 6.0 (> 5.0) OR CPU usage > 85%
      → PID continues adjustment with integral accumulation
      → CPU perf: 52% → 54%, GPU perf: 52% → 54%

[4s]  Target: 60 FPS, Current: 59 FPS, CPU usage: 78%, GPU usage: 75%
      → FPS error = 1.0, usage between thresholds (70%-85%)
      → Maintain current performance, reset PID
      → CPU perf: 54% (unchanged), GPU perf: 54% (unchanged)

[6s]  Target: 60 FPS, Current: 60 FPS, CPU usage: 65%, GPU usage: 60%
      → FPS stable (error < 2.0), usage below 70% threshold
      → Gradual reduction (-2% step)
      → CPU perf: 54% → 52%, GPU perf: 54% → 52%
```
*注: 実際の PID 計算は Kp=0.5、Ki=0.2、Kd=0.1、ADJUSTMENT_DECAY_FACTOR=0.3 を使います。
詳細ログを有効にすると、P/I/D 各項と出力の正確な値を確認できます。*

**ログ出力:**
- `PerformanceAutoTuner(enableLogging = true)` で詳細ログを有効化
- PID 計算をログ出力: 誤差、P/I/D 各項、出力
- チューニングの判断をログ出力: FPS、使用率、性能の調整量
- ハードウェアに適用した周波数／レベルの変更をログ出力

## ファイル構成

```
powercontrol/
├── autotuning/
│   ├── PerformanceAutoTuner.kt        # Automatic performance tuner
│   └── PidController.kt               # PID controller implementation
├── drivers/
│   ├── PerformanceDriver.kt           # Abstract base class
│   ├── PServerDriver.kt               # PServer implementation
│   ├── SamsungPerformanceDriver.kt    # Samsung SDK implementation
│   └── NoOpPerformanceDriver.kt       # No-op fallback implementation
├── profiles/
│   ├── CpuGovernor.kt                 # CPU governor enum
│   └── PerformancePreset.kt           # Performance preset enum
├── PowerManager.kt                    # High-level facade
├── PowerProfile.kt                    # Profile data class and factory
└── README.md                          # This file
```

## 参考資料

### 謝辞

この実装は次のプロジェクトに着想を得ており、参照しています:

- [GameMode](https://github.com/FeralInteractive/gamemode) - オンデマンドでシステム性能を最適化する
  Linux デーモン（Feral Interactive 製）
  - ポリシーベースの CPU 制御の手法
  - sysfs の検証テクニック
  - 堅牢なエラー処理のパターン
- [ClusterTune](https://github.com/AurelioB/ClusterTune) - Android 端末向けの CPU 周波数とガバナーの制御
- [Pulse](https://github.com/keiretrogaming/pulse) - 携帯ゲーミング端末向けのパフォーマンスチューニング

### 参考リンク集

- [Samsung Performance SDK - 概要](https://developer.samsung.com/galaxy-performance/overview.html)
- [Samsung Performance SDK - プログラミングガイド](https://developer.samsung.com/galaxy-performance/programming-guide.html)
- [Samsung Performance SDK - API リファレンス](https://developer.samsung.com/galaxy-performance/api-reference)
- PServer: AYN Odin、Retroid Pocket 端末上の独自 binder サービス
- [Linux の CPU 周波数スケーリング](https://www.kernel.org/doc/html/latest/admin-guide/pm/cpufreq.html)
