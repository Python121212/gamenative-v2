# APK の署名（CI ビルドの更新が入らない問題）

## 症状

以前の APK の上に新しい APK を入れようとすると、Android が

```
アプリがインストールされていません
```

と表示して失敗する。データの削除を伴わない「更新」として入らない。

## 原因

Android は **同じパッケージ名の APK は、署名が一致するときだけ上書き**を許す
（`INSTALL_FAILED_UPDATE_INCOMPATIBLE`）。CI（GitHub Actions）のデバッグビルドは、
これまで `KEYSTORE_BASE64` が未設定だと **ランナー上で自動生成される
`~/.android/debug.keystore`** で署名されていた。ランナーは毎回作り直されるので、
**ビルドごとに署名が変わり**、前回の APK の上に入らなくなっていた。

## 対応

CI 用のデバッグ鍵をリポジトリに置き、デバッグビルドは常にそれを使う。

```
ci-signing/gamenative-ci-debug.p12   # PKCS12, alias/password は gamenative-ci
```

`app/build.gradle.kts` は次の順で署名鍵を選ぶ（上が優先）。

| 優先 | 条件 | 使う鍵 |
|---|---|---|
| 1 | `KEYSTORE_FILE`（`KEYSTORE_BASE64` シークレットから復元） | `fixed` |
| 2 | `ci-signing/gamenative-ci-debug.p12` がある | `ciDebug`（リポジトリ同梱） |
| 3 | どちらも無い | AGP が作る `debug`（ランナーごとに変わる） |

これで CI のデバッグ APK は**毎回同じ署名**になり、
2 回目以降は普通に上書きインストールできる。`Print signing certificate SHA-1` の
ステップに出る SHA-1 も固定される（`Android` 型の OAuth クライアントはこれを使う）。

現在の署名証明書:

```
subject = CN=GameNative CI Debug, O=GameNative, OU=CI, C=JP
SHA-1   = 85:D8:C9:35:B6:83:91:AF:20:9B:7D:45:98:AE:1B:AC:3E:06:90:F2
SHA-256 = A9:52:CF:81:F3:CF:97:13:B3:B7:8C:D7:97:2C:AB:AE:48:39:4C:70:FA:66:A2:05:4F:E1:9D:F5:24:7B:C3:CD
有効期限 = 2056-11-04
```

## すでに旧ビルドが入っている端末

署名が変わる**最初の 1 回だけ**、旧アプリをアンインストールしてから入れ直す。
アンインストールするとアプリのデータ（コンテナ、プレフィックス、設定）も消えるので、
必要なら先に Drive へバックアップする。

```
設定 → アプリ → GameNative → アンインストール
その後 GameNative-<variant>.apk をインストール
```

2 回目以降は、新しい APK をそのままインストールすれば更新できる（データは残る）。

## 鍵を差し替えたい場合

リポジトリ同梱鍵は**公開されたデバッグ鍵**なので、配布用のリリース署名には使わない。
独自の鍵にしたいときは GitHub Secrets に登録すれば上の表の 1 番が優先される。

```bash
keytool -genkeypair -v -keystore release.jks -alias gamenative \
  -keyalg RSA -keysize 2048 -validity 10950
base64 -w0 release.jks   # → GitHub Secrets: KEYSTORE_BASE64

# 併せて登録する Secrets
#   KEYSTORE_PASSWORD / KEY_ALIAS / KEY_PASSWORD
```

ワークフローの `Set up signing key` が `KEYSTORE_BASE64` を `ci-signing/release.jks` に戻し、
`KEYSTORE_FILE` を設定する。この場合 `ci-signing/gamenative-ci-debug.p12` は使われない。

## 鍵を作り直した場合の注意

- 署名が変わるので、利用者は**一度アンインストール**が必要になる。
- `Android` 型 OAuth クライアント（Drive 機能）は SHA-1 で識別されるため、
  Google Cloud Console 側の登録も更新する。
- ローカルビルド（`./gradlew :app:assembleLegacyDebug`）も同じ鍵を使う。
  Android Studio に任せたい場合は `KEYSTORE_FILE` を渡さず、
  `ci-signing/gamenative-ci-debug.p12` を削除すれば従来どおり `~/.android/debug.keystore` になる。
