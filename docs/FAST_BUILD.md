# APK を速く作る

「APK 生成に 1 時間かかる」の正体は、たいてい **R8（難読化）＋リソース圧縮付きの
リリース変種**か、**初回の依存ダウンロード**、あるいは**メモリ不足によるスワップ**。
デバッグ変種は R8 をしないので、同じコードでも桁違いに速い。

実測（このリポジトリ、GitHub Actions `ubuntu-latest`）:

| ビルド | 時間 |
|---|---|
| デバッグ APK（キャッシュが効いた 2 回目以降） | 約 2 分 |
| デバッグ APK（キャッシュなし・初回相当） | 約 11 分 |
| リリース AAB（3 変種・R8・直列） | 数十分（`app-release-signed.yml`） |

このリポジトリは**ネイティブのコンパイルをしない**（`jniLibs` のビルド済み `.so` を同梱）。
時間がかかるのは次の 3 つだけ。

1. 依存のダウンロード（初回のみ、ネットワーク次第）
2. Kotlin + Compose のコンパイル
3. リリース変種の R8 とリソース圧縮（と 44 MB の APK パッケージング）

## ローカル: まずこれ

```bash
./scripts/apk.sh            # :app:assembleLegacyDebug（R8 なし・最速）
```

中でやっていること:

- `--build-cache` で前回の出力を再利用
- `--max-workers` をコア数に合わせる
- `clean` はしない（差分ビルドが壊れるだけ）

初回だけは依存の取得に時間がかかるので、先に落としておくと後が速い。

```bash
./scripts/apk.sh deps       # 依存だけ解決（放置してよい）
OFFLINE=1 ./scripts/apk.sh  # 以降はネットワーク待ちなし
```

メモリが 8 GB 以下の端末では、ヒープを絞ったほうが速いことが多い（スワップ回避）。

```bash
LOW_RAM=1 ./scripts/apk.sh
```

## やりがちな「遅い」パターン

| やりがち | なぜ遅い | 代わりに |
|---|---|---|
| `./gradlew build` | 全変種のテスト・lint・リリースまで走る | `./scripts/apk.sh` |
| `./gradlew assembleLegacyRelease` | R8 + リソース圧縮 + 44 MB のパッケージング | 反復中は debug。リリースを速く見たいときは `./scripts/apk.sh release`（`-PfastRelease` で R8 を切る） |
| `./gradlew clean assembleDebug` | 毎回ゼロから。`clean` は差分を捨てるだけ | `clean` は依存やタスク構成を変えたときだけ |
| ビルドのたびに `./gradlew --stop` | デーモン・Kotlin daemon の起動を毎回払う | 終わったら `./scripts/apk.sh stop` を 1 回だけ |
| Android Studio で「Generate Signed Bundle / APK」 | リリース変種＝R8 が走る | 動作確認は「Build > Build APK(s)」 |
| 4 変種すべてを手元で作る | 変種ごとに別ビルド | 必要な 1 つだけ（`legacy` が既定） |

`-PfastRelease` は **配布物用ではありません**（難読化と圧縮を切るので）。あくまで
「リリース変種の挙動を手元で確かめたい」ときの短縮用。

```bash
./gradlew :app:assembleLegacyRelease -PfastRelease --build-cache
```

## どこに時間がかかっているか測る

```bash
./scripts/apk.sh measure
# → app/build/reports/profile/profile-*.html にタスク別の所要時間が出る
```

`--scan` を使えるなら `./gradlew :app:assembleLegacyDebug --scan` が最も詳しい。

## CI（GitHub Actions）

`.github/workflows/build-apk.yml` は手動実行のみ。

| 入力 `variant` | 動き |
|---|---|
| `legacy`（既定）/ `modern` / `legacyXr` / `modernXr` | その 1 つをビルド |
| `all` | `legacy` と `modern` を**並列ジョブ**でビルド（直列だと単純に倍かかる） |

`gradle/actions/setup-gradle` が Gradle のユーザーホーム（依存キャッシュと
`caches/build-cache-1`）を保存するので、**2 回目以降は数分**で終わる。
キャッシュが効かないのは次のときだけ。

- 7 日以上ワークフローを動かしていない（キャッシュの有効期限）
- `gradle/libs.versions.toml` や AGP/Gradle のバージョンを変えた
- 初回、または `ci-signing/gamenative-ci-debug.p12` を差し替えた直後

APK は実行ページ下部の Artifacts から取れる（`gamenative-<variant>-debug`）。
署名は固定なので、前回の上にそのままインストールできる（[BUILD_SIGNING.md](BUILD_SIGNING.md)）。

## さらに速くしたいとき

- **並列度**: `MAX_WORKERS=8 ./scripts/apk.sh`（コア数が多い端末）
- **JVM**: `gradle.properties` の `org.gradle.jvmargs` は 8 GB 前提。4 GB 端末なら
  `-Dorg.gradle.jvmargs=-Xmx3g` を付ける（`LOW_RAM=1` がこれをやる）
- **リリースの本数**: `app-release-signed.yml` は 3 変種を直列に作っている。
  並列ジョブに割ると壁時計時間は約 1/3 になるが、署名・bundletool の手順も
  変種ごとに分ける必要があるので、必要になったら着手する
- **Kotlin の増分**: `kotlin.incremental=true`（既定）を維持し、`build/` を消さない
