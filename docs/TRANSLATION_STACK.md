# 多重翻訳の排除（Translation Stack）

Windows のゲームを Android 端末で動かすとき、必ず次の 4 段で「翻訳」が起きる。

```
x86-64 の実行ファイル ──[CPU 翻訳]──▶ AArch64 ネイティブ
Win32 API            ──[Windows ランタイム]──▶ Linux (Android)
Direct3D 8 / 9 / 10 / 11 / 12 ──[Direct3D 翻訳]──▶ Vulkan または OpenGL
グラフィックス API    ──[GRAPHICS_API]──▶ Vulkan または GL
Vulkan               ──[GPU ドライバ]──▶ 実際の GPU ドライバ（ICD は 1 つだけ）
```

各段で有効なレイヤーは **ちょうど 1 つ**でなければならない。2 つ以上が同時に有効になっている状態を
本ドキュメントでは **多重翻訳** と呼ぶ。多重翻訳は次の害を生む。

- 同じ API を 2 つの層が担当し、片方は設定されないまま動く（例: vkd3d を選ぶと DXVK も入るのに
  `DXVK_*` が設定されず、シェーダーキャッシュ・フレームレート制限・`dxvk.conf` が効かない）。
- 前回の設定が残り、選択していない層の設定が効き続ける（例: DXVK から wined3d に変えても `DXVK_*` が残る）。
- 担当が二重になり、片方が静かに上書きされる（例: DXVK 2.x は `d3d8.dll` を同梱するのに D8VK も展開する）。
- 出力先と入力先が噛み合わない（例: D3D→Vulkan の翻訳を選んだのに、グラフィックスドライバは GL 専用の virgl）。

## 設計

判定は `TranslationStackResolver`（純粋関数）に集約し、起動直前に 1 回だけ適用する。

```
app/src/main/java/app/gamenative/translation/
├── TranslationStack.kt          # 段・レイヤー・計画・競合のデータモデル
├── TranslationStackResolver.kt  # 設定値から「1 本の計画」を決める純粋ロジック
└── TranslationStackApplier.kt   # Container / EnvVars への橋渡しとログ
```

### 1. 段ごとに 1 レイヤーを決める

| 段 | レイヤー | 決め方 |
|---|---|---|
| CPU | `fexcore` / `box64` | arm64ec の wine なら `container.emulator`、それ以外の wine は常に box64（ランチャーが `bin/box64` 経由で起動するため） |
| Windows | `wine-bionic` / `wine-glibc` | `container.containerVariant` |
| Direct3D | `dxvk` / `vkd3d` / `wined3d` / `cnc-ddraw` | `container.dxWrapper` と `dxwrapperConfig` の `ddrawrapper` |
| GRAPHICS_API | `vulkan` / `gl` | `container.graphicsDriver`（`virgl` のみ GL 専用） |
| GPU_DRIVER | `turnip-icd` / `adrenotools-icd` / `vortek-icd` / `virgl-gl` | `graphicsDriver` と `graphicsDriverConfig` の `adrenotoolsTurnip` / `adrenotoolsDriver` |

Direct3D 段だけは 2 レイヤーを許す。`vkd3d` を選ぶと D3D12 は vkd3d、D3D9〜11 は DXVK が受け持つため、
**API ごとの担当分け**として 1 本の計画にまとめる（`TranslationStackPlan.isSingleLayerPerStage`）。

### 2. 死んでいる層の環境変数を落とす

有効でない層に属する環境変数は実行時に読まれないので、安全に削除できる。これを
`TranslationStackPlan.deadEnvKeys` として列挙し、起動直前に `EnvVars.remove()` する。

| 除去するもの | 条件 |
|---|---|
| `BOX64_*` / `BOX86_*` | CPU 段が FEXCore のとき |
| `FEX_*` | CPU 段が Box64 のとき |
| `PROTON_*` / `STEAM_COMPAT_*` / `UMU_*` | bionic コンテナ（Proton のスクリプトを通らない）。ただし `PROTON_DISABLE_LSTEAMCLIENT` は Steam 起動経路が両方で使うため除外 |
| `DXVK_*` | D3D 段が DXVK を使わないとき（wined3d / cnc-ddraw のみ） |
| `VKD3D_*` | D3D 段が vkd3d を使わないとき |
| `CNC_DDRAW_CONFIG_FILE` | DirectDraw の担当が cnc-ddraw でないとき |
| `TU_*` | ICD が Turnip でないとき |
| `ADRENOTOOLS_*` | ICD が Adrenotools／Vortek でないとき |
| `VORTEK_*` | ICD が Vortek でないとき |
| `VIRGL_*` | ICD が Virgl でないとき |

### 3. 消すと危険な多重化は記録する

環境変数を消すだけでは直らない（あるいは消すと壊れる）ものは、記録して可視化する。

| kind | 意味 | 動作 |
|---|---|---|
| `duplicate-cpu-translator` | FEXCore と Box64 の設定が両方ある | 死んだ側の環境変数を除去 |
| `vkd3d-composite-unconfigured-dxvk` | vkd3d の計画なのに `DXVK_*` が無い | 起動側で DXVK の設定も入れるよう修正 |
| `builtin-override-shadows-dxvk` | `WINEDLLOVERRIDES` が `d3d9=b` などで DXVK を wined3d に固定している | 記録のみ（ゲーム個別の修正と衝突するため、勝手に外さない） |
| `vulkan-translator-without-icd` | DXVK/VKD3D を選んだのにドライバが GL 専用（virgl） | 記録のみ（DLL の入れ替えは起動時には行わない） |
| `extra-gl-bridge` | DXVK 直結なのに zink（Vulkan→GL）も有効 | 記録のみ（OpenGL タイトルには必要） |
| `duplicate-gpu-driver-providers` | GPU ドライバが 2 系統以上設定されている（例: `TU_*` と `ADRENOTOOLS_*`） | 未使用側の環境変数を除去 |
| `icd-manifest-duplicate` | Vulkan ローダに渡す ICD が複数（`VK_ICD_FILENAMES` と `VK_DRIVER_FILES` の併用など） | 1 件に畳む |
| `icd-manifest-mismatch` | 選択中のドライバと別の ICD マニフェストを指している | マニフェストを選択中のドライバのものへ書き換え |
| `vulkan-icd-on-gl-driver` | GL 専用の virgl なのに Vulkan ICD が設定されている | 記録のみ（ユーティリティが Vulkan を使う可能性がある） |

## GPU ドライバ（ICD）段

Vulkan ローダが読み込む ICD は **1 つだけ**にする。アプリが imagefs の share 配下で管理している
マニフェストは次の 3 つで、選んだドライバによって期待値が決まる。

| ICD | マニフェスト | 条件 |
|---|---|---|
| `turnip-icd` | `freedreno_icd.aarch64.json` | `graphicsDriver=turnip` かつ `adrenotoolsTurnip=0`（Turnip 直結） |
| `adrenotools-icd` | `wrapper_icd.aarch64.json` | `graphicsDriver=turnip`（Adrenotools 経由）／`wrapper` |
| `vortek-icd` | `wrapper_icd.aarch64.json` | `graphicsDriver=vortek` / `adreno` / `sd-8-elite`（in-process レンダラ） |
| `virgl-gl` | なし | `graphicsDriver=virgl`（GL 専用。Vulkan を受け取る先が無い） |

適用時の扱い:

- `VK_ICD_FILENAMES` / `VK_DRIVER_FILES` が**アプリ管理のマニフェスト**を指している場合だけ、
  選択中のドライバのマニフェストへ書き換える（ディレクトリは元の `…/vulkan/icd.d` を維持し、
  分からなければ `share/vulkan/icd.d` を使う）。
- ゲームスコープのセッション ICD（`gamescope/share/vulkan/icd.d/adrenotools_gamescope.aarch64.json`）など
  **アプリ管理外**のパスは触らない。
- 両方の環境変数が設定されていれば `VK_ICD_FILENAMES` に統一する（既存コードがこちらを使っている）。
- `virgl` では Vulkan ICD を残す（記録のみ）。GL 経路では使われないが、補助ツールが Vulkan を使う可能性がある。

## 適用ポイント

1. `XServerScreen.setupXEnvironment()` — 起動直前の環境変数がそろった地点で 1 回適用する。
   ここではゲーム個別修正（gamefix）や MOD の `WINEDLLOVERRIDES` も既にマージ済み。
2. `BionicProgramLauncherComponent.start()` — ランチャー内部が box64 と FEXCore の両方の
   プリセット環境変数を積んだ**後**に適用する。ここが CPU 段の最終的な合流点。

どちらも失敗しても起動は止めない（判定に失敗したら環境変数をそのまま使う）。

## あわせて直した既存の多重化

1. **D8VK の二重展開**（`extractDXWrapperFiles`）
   DXVK 2.0 以降は `d3d8.dll` を同梱するため、単体の D8VK を重ねると D3D8 の担当が二重になる。
   選択中の DXVK が 2.0 以上なら D8VK を展開しない（版が読めない場合とユーザー定義プロファイルは
   従来どおり展開）。
2. **vkd3d 計画で DXVK が未設定のまま動く問題**（`extractGraphicsDriverFiles` の 2 箇所）
   vkd3d を選ぶと D3D9〜11 用に DXVK の DLL も展開されるのに、環境変数は `VKD3D_*` しか設定されて
   いなかった。`DXVKHelper.setEnvVars()` も呼び、シェーダーキャッシュ・フレームレート制限・
   `dxvk.conf` が効く状態に揃える。

## ログ

起動ごとに 1 行出る。デバッグ時は目視で多重翻訳の有無を確認できる。

```
TranslationStack: translation-stack cpu=fexcore win=wine-bionic d3d=dxvk-2.7.1 gfx=vulkan-turnip icd=adrenotools-icd-vulkan.adreno.so hops=5 dedup=9 conflicts=1 single=true
TranslationStack: x86-64 → fexcore-2508 → aarch64 ; win32 → wine-bionic ; d3d → dxvk-2.7.1 → vulkan-turnip → adrenotools-icd-vulkan.adreno.so
TranslationStack: ICD を 1 つに固定: VK_ICD_FILENAMES=/data/share/vulkan/icd.d/freedreno_icd.aarch64.json → /data/share/vulkan/icd.d/wrapper_icd.aarch64.json
```

## 今後（同じ枠組みで扱えるもの）

- **ICD の実体の一本化**: 現在は「どのマニフェストを渡すか」を 1 つに固定している。マニフェスト自体が
  古い・壊れている場合の再展開（`ContentsManager` の信頼ファイル一覧を使った検証）は次の段階。
- **per-game の計画の保存**: 現在は起動のたびに設定値から再計算する（キャッシュ不要で常に設定と一致する）。
  計画を保存して UI で可視化すれば、ユーザーが「どの翻訳を通っているか」を選べるようになる。
- **テスト**: `app/src/test/java/app/gamenative/translation/TranslationStackResolverTest.kt` が
  多重化の判定・除去・競合記録を端末なしで検証する。新しいレイヤーを足すときは、まずここにケースを足す。

## 起動に失敗したとき

翻訳段の設定は「なぜ落ちたか」の主要な原因になる（ドライバ／翻訳層の組み合わせ、未対応命令など）。
異常終了時は `CrashFingerprint` と `RuleDiagnoser` がその組み合わせを判定し、
上の表にある設定（`graphicsDriver` / `dxwrapper` / `emulator` など）を対処として提示する。
詳細は [AI_DIAGNOSTICS.md](AI_DIAGNOSTICS.md)。
