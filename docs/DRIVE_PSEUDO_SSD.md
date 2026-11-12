# Google Drive 疑似SSD (Pseudo-SSD)

GameNative のストレージとして **Google Drive** を使う機能です。
Google Drive はファイルシステムではなくオブジェクトストレージなので、ファイルを
直接渡すことはできません。そのため **「書き終えたファイル / フォルダー」を順番に
アップロードする** 方式にしています。

- 1アカウントあたり **15 GB**（無料枠）を上限として扱う
- **複数アカウントを登録可能**。合計容量は `15 GB × アカウント数`
- アップロードは **直列キュー**（`DriveUploadQueue`）。同時アップロードはしない
- 空き容量が足りない場合は、空きのある別アカウントへ自動で切り替える

## 構成

| ファイル | 役割 |
| --- | --- |
| `service/drive/DriveModels.kt` | `DriveAccount` / `DriveFileEntry` / `DriveUploadTask` / 合計容量モデル |
| `service/drive/DriveAccountStore.kt` | アカウント・ファイル索引・キューの JSON 永続化 (`drive_pseudo_ssd.json`) |
| `service/drive/DriveOAuthClient.kt` | ブラウザでアカウント選択する OAuth + PKCE ログイン（Android クライアント）、トークン更新 |
| `service/drive/DriveOAuthRedirectActivity.kt` | ブラウザからのリダイレクト（カスタムスキーム）受け取り |
| `service/drive/DriveAuthManager.kt` | アクセストークンのキャッシュと Drive サービス生成 |
| `service/drive/DriveApiClient.kt` | Drive API（再開可能アップロード / ダウンロード / 削除 / 容量取得） |
| `service/drive/DriveUploadQueue.kt` | 直列アップロードのワーカー（再試行3回、アカウント切替、SHA-256） |
| `service/drive/DriveArchiveHelper.kt` | フォルダーの zip 化 / 展開（zip-slip 対策込み） |
| `service/drive/DriveStorageManager.kt` | 高レベル窓口（`saveFile` / `saveFolder` / `restoreFile` / `restoreFolder` / 容量） |
| `service/drive/DriveAutoBackup.kt` | 「書き終えたファイル」をキューへ渡すブリッジ |
| `ui/screen/settings/DriveStorageDialog.kt` | 設定画面のダイアログ（アカウント追加・残量・保存済み一覧） |
| `ui/screen/settings/SettingsGroupDriveStorage.kt` | 設定の「ストレージ」セクション項目 |

## 使い方（アプリ内）

1. 設定 → **ストレージ** → **Google Drive 疑似SSD**
2. **Googleアカウントを追加** でアカウントを登録（繰り返せば複数アカウント）
3. **書き終えたファイルを自動でアップロード** をオンにすると、
   セーブバックアップの zip など「完成したファイル」が順次アップロードされます

コード上からは次のようにキューへ投入できます。

```kotlin
val drive = DriveStorageManager.getInstance(context)

// ファイル（完成済みのもの）
drive.saveFile(File("/path/to/finished.iso"), displayName = "game.iso")

// フォルダー（内部で zip 化してから順次アップロード）
drive.saveFolder(File("/path/to/prefix"))

// 自動フック（設定がオンのときだけ動く）
DriveAutoBackup.enqueueIfEnabled(context, finishedFile)
```

## ログインのしかた（アカウント選択だけ）

1. 設定 → **ストレージ** → **Google Drive 疑似SSD**
2. **Googleアカウントを追加** を押す
3. ブラウザが開くので **Google アカウントを選ぶ**（初回のみ「許可」をタップ）
4. 自動でアプリに戻り、登録完了 — 増やしたいときは同じ操作を繰り返す

コード入力・パスワード入力・アプリ再起動は不要です。

### 仕組み

- OAuth 2.0 **認可コード + PKCE**、OAuth クライアントの種類は **Android**
- リダイレクトは `com.googleusercontent.apps.<クライアントID>:/oauth2redirect`
  → `DriveOAuthRedirectActivity` が受け取り、自動でアプリに戻ります
- Android クライアントは **パブリッククライアント**なので、
  **クライアントシークレットは不要**（APK に秘密を埋め込みません）
- 取得した **リフレッシュトークン** でバックグラウンドのアップロードを継続
- リフレッシュトークンはアプリ専用領域の `drive_pseudo_ssd.json` にのみ保存

## 事前準備（Google Cloud / OAuth）— 1回だけ

1. Google Cloud Console でプロジェクトを作成
2. **Google Drive API** を有効化
3. **OAuth 同意画面** を設定し、スコープ `.../auth/drive.file` を追加
4. **認証情報 → OAuth クライアント ID → アプリの種類: Android** を作成
   - パッケージ名: `app.gamenative`
   - SHA-1: ビルドした APK の署名証明書フィンガープリント
5. 発行された **クライアントID** をビルドに設定
   ```
   # local.properties
   GOOGLE_OAUTH_CLIENT_ID=xxxxxxxx.apps.googleusercontent.com
   ```
   GitHub Actions の場合は Secrets の `GOOGLE_OAUTH_CLIENT_ID` に登録すれば
   ビルド時に自動で埋め込まれます。

> **クライアントIDはビルド時に埋め込む必要があります。**
> リダイレクトのスキーム名がクライアントIDから決まるため、後からアプリ内で
> 貼り付ける方式は使えません。

### SHA-1 の確認方法

署名が一致しないとログインできません（`DEVELOPER_ERROR`）。

```bash
# ビルド済み APK から
keytool -printcert -jarfile gamenative-legacy-debug.apk | grep SHA1

# キーストアから
keytool -list -v -keystore <keystore> -alias <alias> | grep SHA1
```

Actions のビルドでは **`Print signing certificate SHA-1`** ステップが
APK の SHA-1 をログに出力します。

> **CI のデバッグビルドは署名が固定されています。**
> `ci-signing/gamenative-ci-debug.p12` で署名するため SHA-1 は
> **`85:D8:C9:35:B6:83:91:AF:20:9B:7D:45:98:AE:1B:AC:3E:06:90:F2`** で変わりません。
> Google Cloud に一度登録すれば、以後の CI ビルドでもそのままログインできます
> （署名の詳細は [BUILD_SIGNING.md](BUILD_SIGNING.md)）。
> 自分で鍵を差し替えた場合（`KEYSTORE_BASE64` など）は、その SHA-1 を登録してください。

### 他の人もログインできる？

- 同じ APK（同じ署名）を配れば、**各自が自分の Google アカウントを選ぶだけ**で使えます
- 同意画面が **テスト** のままだと、コンソールで「テストユーザー」に追加した
  アカウント（最大100）しかログインできません。**本番（In production）** にすると
  誰でもログインできます（制限付きスコープのため未審査の間は警告画面が出ます）
- **テスト** 状態のリフレッシュトークンは7日で失効します。常用するなら本番に切り替えてください
- 各自が自分でビルドする場合は、そのビルドの SHA-1 を Google Cloud に追加する必要があります

## アプリ内の操作（日本語 UI の実際の表示）

1. 下部の **設定** タブを開く
2. セクション **ストレージ**（アイコン: 雲に上向き矢印）を開く
3. **Google Drive 疑似SSD** をタップ
4. **Googleアカウントを追加** をタップ
5. ブラウザが開く → アカウントを選ぶ → 初回は「許可」→ 自動でアプリに戻る
6. ダイアログに `1 アカウント · 0 B / 15 GB` のように表示されれば登録完了
7. **書き終えたファイルを自動でアップロード** をオンにすると、完成したファイルが順次送られる

アカウントを増やすときは 4 を繰り返す（1 アカウント 15 GB ずつ増える）。
保存済みの項目は同じダイアログの **保存済みの項目** に並び、**復元** / 削除ができる。

## うまくいかないとき

| 表示 | 原因 | 対処 |
|---|---|---|
| `このビルドには OAuth クライアントIDが入っていません` | ビルド時に `GOOGLE_OAUTH_CLIENT_ID` が渡されていない | `local.properties` か GitHub Secrets に設定して**再ビルド**（アプリ内では設定できない） |
| `ログインに失敗しました: access_denied` | OAuth 同意画面でスコープ `drive.file` が未設定、またはテスト状態で自分のアカウントが「テストユーザー」に入っていない | 同意画面を確認。テスト中は使うアカウントをテストユーザーに追加 |
| `ログインに失敗しました: invalid_request` / `DEVELOPER_ERROR` | OAuth クライアントの **パッケージ名**（`app.gamenative`）か **SHA-1** が APK と一致していない | 登録値を APK の署名と突き合わせる（下記コマンド） |
| ブラウザに「このアプリは Google の確認を受けていません」 | 同意画面が未審査（制限付きスコープ） | 「詳細」→「（安全でないページに）移動」で続行。常用するなら本番公開へ |
| 数日でログインが切れる | 同意画面が**テスト**状態だとリフレッシュトークンが 7 日で失効 | 同意画面を **本番（In production）** にする |
| `アプリがインストールされていません` | APK の署名が前回と違う（OAuth ではなくインストールの問題） | [BUILD_SIGNING.md](BUILD_SIGNING.md) |

APK の SHA-1 は署名鍵から直接確認できる。

```bash
openssl pkcs12 -in ci-signing/gamenative-ci-debug.p12 -passin pass:gamenative-ci \
  -clcerts -nokeys | openssl x509 -noout -fingerprint -sha1
```

## 動作の詳細

- **直列アップロード**: `DriveUploadQueue` はキューの先頭から 1 件ずつ処理します。
  Drive にはファイルハンドルを渡せないため、アップロードは「完成したファイルの
  バイト列を送る」形になり、途中で割り込む並列転送は行いません。
- **再開可能アップロード**: `MediaHttpUploader` によりチャンク分割で送信するため、
  数 GB のゲームファイルでも通信断から再開できます。
- **永続キュー**: 未完了のアップロードは `drive_pseudo_ssd.json` に保存され、
  アプリ再起動後も続行されます。
- **容量管理**: アカウントごとの使用量はアップロード完了時に加算し、
  `about().get().setFields("storageQuota")` で実測値に更新できます。
  1アカウントの上限は既定 15 GB（`DriveAccount.DEFAULT_QUOTA_BYTES`）。
- **整合性**: アップロード前に SHA-256 を計算して索引に保存します。
- **フォルダー**: フォルダーは zip にまとめて 1 ファイルとして保存し、
  復元時に展開します（`../` などは拒否）。

## 制限

- 15 GB は無料アカウントの目安です。実際の上限は Google アカウントの契約
  （Google One など）に依存し、実測値は Drive の `storageQuota` を優先します。
- 共有ドライブやチームドライブは対象外（マイドライブのみ）。
- アップロードは直列のため、大量のファイルは時間がかかります。
