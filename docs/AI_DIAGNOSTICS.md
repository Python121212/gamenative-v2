# AI によるエラー自動診断

ゲームが異常終了したとき、**何が起きたのかを自動で判定**して、設定の変更という形で「次の一手」を出す仕組み。

設計の柱は 3 つ。

1. **端末内のルール判定が常に動く**（ネットワーク不要・無料・即時）。
2. **AI は任意**。ビルド時に API キーが入っているときだけ、ルールで確度が足りない場合に問い合わせる。
3. **AI の出力はそのまま設定に流さない**。許可リスト（[DiagnosisWhitelist]）を通った対処だけを採用する。

## 全体の流れ

```
ゲーム異常終了
   │  GuestOutputTail（ゲストの stdout/stderr の末尾 256 KB）
   ▼
CrashFingerprint.extract        … 既存の仕組み。component / kind / code / hash を作る
   │
   ▼
LogDigest.digest                … 伏字化・ノイズ除去・重複の畳み込み（最大 200 行 / 8,000 字）
   │
   ├─ RuleDiagnoser.diagnose    … 端末内ルール。確度 0.7 以上なら確定
   │
   └─ AiDiagnosisClient         … 設定があるときだけ。0.7 未満のときの補完
   │
   ▼
Diagnosis（推定原因＋対処＋確度）
   │
   ├─ files/aidiag/<hash>.json に保存（同じ失敗は 7 日間キャッシュ＝AI を二度呼ばない）
   ├─ Timber に 1 行（`診断 (source=RULE conf=0.75 dxvk/device_lost/…): …`）
   └─ Snackbar に短く表示（推定原因と対処 2 件まで）
```

呼び出しは `XServerScreen` の異常終了ハンドラ 1 か所だけ。

```kotlin
if (status != 0) {
    DiagnosisService.requestDiagnosis(
        context = context,
        container = container,
        exitReason = "exit_$status",
        tail = GuestOutputTail.snapshot(),
    )
}
```

すべてバックグラウンド（`Dispatchers.IO`）で走り、失敗しても終了処理は止まらない。

## 端末内ルール（`RuleDiagnoser`）

| # | 症状（フィンガープリント／ログ） | 推定原因 | 提案する対処 | 確度 |
|---|---|---|---|---|
| 1 | `device_lost` / `VK_ERROR_DEVICE_LOST` / GPU hang | ドライバか翻訳層の不整合 | `graphicsDriver=turnip`、`WRAPPER_NO_PATCH_OPCONSTCOMP=1` | 0.75 |
| 2 | `VK_ERROR_OUT_OF_DEVICE_MEMORY` など | VRAM 不足 | `DXVK_FRAME_RATE=30`、`graphicsDriver=turnip` | 0.70 |
| 3 | FEXCore の未実装命令・未対応 syscall | FEXCore の未対応 | `emulator=Box64`、`fexcorePreset=COMPATIBILITY` | 0.70 |
| 4 | Box64 の未実装オペコード・未対応 syscall | Box64 の未対応 | `emulator=FEXCore`、`BOX64_DYNAREC=0` | 0.65 |
| 5 | `err:module:import_dll` で d3d9/11/12/dxgi | 展開不良か構成の不一致 | `dxwrapper=wined3d`（切り分け） | 0.68 |
| 6 | Wine の未実装関数（d3d 系） | wined3d のカバー外 | `DXVK_ASYNC=0` | 0.62 |
| 7 | シェーダーのコンパイル失敗（vkd3d-shader / E5017） | 翻訳層のシェーダー非対応 | `dxwrapper=wined3d`、`DXVK_ASYNC=0` | 0.60 |
| 8 | `VK_ERROR_EXTENSION_NOT_PRESENT` / `INCOMPATIBLE_DRIVER` | ICD が端末の Vulkan に合わない | `graphicsDriver=turnip` | 0.70 |
| 9 | 手がかりの無い異常終了 | 情報不足 | （対処は出さず AI に回す） | 0.25 |

## AI 診断（`AiDiagnosisClient`）

- OpenAI 互換の chat completions を想定。
- 送るのは**伏字化済みのダイジェスト**と、コンテナ構成（variant / wine / emulator / dxwrapper / graphicsDriver / 各バージョン）だけ。
- 応答は JSON ではなく**行指向の固定書式**（パースが決定的で、壊れた応答をそのまま捨てられる）:

```
SUMMARY: GPU ドライバがハングした
CAUSE: Adrenotools のドライバがシェーダーを処理できなかった
FIX: container|graphicsDriver|turnip|Turnip のほうが安定する
FIX: env|DXVK_ASYNC|0|非同期コンパイルを切る
CONFIDENCE: 0.8
```

### 有効化（ビルド時）

```bash
AI_DIAGNOSTICS_API_KEY=sk-... \
AI_DIAGNOSTICS_MODEL=gpt-4o-mini \
AI_DIAGNOSTICS_ENDPOINT=https://api.openai.com/v1/chat/completions \
./gradlew :app:assembleModernDebug
```

`gradle.properties` か環境変数で渡す（`app/build.gradle.kts` の `secret()`）。**未設定が既定**で、
その場合はルール判定のみが動く（外部送信は一切なし）。

## 許可リスト（`DiagnosisWhitelist`）

AI にもルールにも共通で、**次のキー・値しか設定候補として認めない**。

| scope | key | 許される値 |
|---|---|---|
| container | `graphicsDriver` | `turnip` / `vortek` / `virgl` / `wrapper` / `adreno` / `sd-8-elite` |
| container | `dxwrapper` | `dxvk-<版>` / `vkd3d-<版>` / `wined3d` / `cnc-ddraw` |
| container | `emulator` | `FEXCore` / `Box64` |
| container | `containerVariant` | `bionic` / `glibc` |
| container | `fexcorePreset` | `STABILITY` / `COMPATIBILITY` / `INTERMEDIATE` / `PERFORMANCE` / `EXTREME` / `DENUVO` |
| env | `DXVK_ASYNC` / `DXVK_GPLASYNCCACHE` / `BOX64_DYNAREC` / `FEX_TSOENABLED` / `FEX_X87REDUCEDPRECISION` / `WRAPPER_NO_PATCH_OPCONSTCOMP` / `vblank_mode` | `0` / `1` |
| env | `DXVK_FRAME_RATE` | 1〜240 |
| env | `DXVK_HUD` / `TU_DEBUG` / `MESA_VK_WSI_DEBUG` / `VKD3D_DEBUG` | 短い識別子のみ（制御文字・改行不可） |

これ以外（`LD_PRELOAD`、任意パス、存在しないドライバ名など）は**パース時点で捨てる**。
値は 120 文字まで、制御文字を含むものは拒否。

## 伏字化（`LogDigest`）

| 対象 | 置換 |
|---|---|
| `/data/user/0/...` `/storage/emulated/0/...` `/storage/<vol>/...` | `/<path>` |
| `/home/<user>/...` | `/home/<user>` |
| `C:\Users\<name>\...` | `C:\Users\<user>\...` |
| メールアドレス | `<email>` |
| `Bearer …` / `api_key=` / `token=` / `password=` | `<redacted>` |
| 32 桁以上の 16 進（トークン・ハッシュ） | `<hex>` |
| SteamID64 | `<steamid>` |
| IPv4 | `<ip>` |

技術的な手がかり（`VK_ERROR_DEVICE_LOST`、`d3d11.dll`、`err:module:import_dll` など）は残す。

## 保存とキャッシュ

- 保存先: `files/aidiag/<fingerprint hash>.json`（アプリ内部。外部には出さない）。
- 同じフィンガープリントは 7 日間キャッシュし、AI を再呼び出ししない（コストと遅延の抑制）。
- 記録形式は `Diagnosis.toRecordJson()` / `Diagnosis.fromRecordJson()`。壊れていれば黙って捨てる。

## テスト

`app/src/test/java/app/gamenative/aidiag/DiagnosisTest.kt`

- `LogDigestTest` — 伏字化（パス・資格情報・メール・SteamID）、技術情報の保持、重複の畳み込み、末尾優先の切り詰め
- `DiagnosisWhitelistTest` — 未知キー・危険値・改行入り値の拒否、重複キーの畳み込み
- `RuleDiagnoserTest` — 各ルールの判定と提案、材料が無いときは何も返さないこと
- `AiDiagnosisParserTest` — 定型応答の読み取り、許可外対処の破棄、SUMMARY 必須、確度のクランプ
- `DiagnosisCacheTest` — 保存と読み戻し

CI（`Build APK (Debug)`）は `app.gamenative.aidiag.*` をコンパイルし、このスイートを実行する。

## 今後

- **UI**: 現在は Snackbar と Timber、ファイル保存のみ。クラッシュダイアログやサポート画面に
  「推定原因」と「対処を適用」ボタンを出す（`OptimizeApplier` と接続）と、そのまま直せるようになる。
- **設定 UI**: API キーは現状ビルド時のみ。アプリ内設定（`PrefManager`）に移す。
- **送信の同意**: AI を既定で有効にするなら、初回に「ログの一部（個人情報を除去）を送る」ことの同意を得る。
- **類似クラッシュの集約**: フィンガープリントは既にハッシュ化されているので、同じ原因の再発を数える仕組みに使える。
