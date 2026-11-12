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
| `service/drive/DriveOAuthClient.kt` | ブラウザでアカウント選択する OAuth + PKCE ログイン、トークン更新 |
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
4. アプリに戻ると登録完了 — 以降はアカウントを増やすときだけ同じ操作

コード入力・パスワード入力・アプリ再起動は不要です。

### 仕組み

- OAuth 2.0 **認可コード + PKCE**、リダイレクトは `http://127.0.0.1:<空きポート>`
  （アプリ内で一時的にローカルHTTPサーバーを立てて受け取ります）
- そのため **署名証明書（SHA-1）に縛られません**。1つのクライアントIDで、
  どのビルド・誰の端末でも同じようにログインできます
- 取得した **リフレッシュトークン** を使ってバックグラウンドのアップロードを継続
- リフレッシュトークンはアプリ専用領域の `drive_pseudo_ssd.json` にのみ保存

## 事前準備（Google Cloud / OAuth）— 1回だけ

1. Google Cloud Console でプロジェクトを作成
2. **Google Drive API** を有効化
3. **OAuth 同意画面** を設定し、スコープ `.../auth/drive.file` を追加
4. **認証情報 → OAuth クライアント ID → アプリの種類: デスクトップ アプリ** を作成
5. 発行された **クライアントID / クライアントシークレット** を次のどちらかで設定

   - **アプリ内**: 設定 → Google Drive 疑似SSD → *OAuth クライアント設定* に貼り付け
   - **ビルドに埋め込む**: `local.properties` に追記（配布APKはこちらが便利）
     ```
     GOOGLE_OAUTH_CLIENT_ID=xxxxxxxx.apps.googleusercontent.com
     GOOGLE_OAUTH_CLIENT_SECRET=xxxxxxxx
     ```
     GitHub Actions でビルドする場合は Secrets の
     `GOOGLE_OAUTH_CLIENT_ID` / `GOOGLE_OAUTH_CLIENT_SECRET` に登録すれば自動で埋め込まれます。

> デスクトップ アプリ種別のクライアントは SHA-1 登録が不要なので、
> ビルドごとに署名が変わるデバッグAPKでも設定をやり直す必要がありません。

### 他の人もログインできる？

- **できます。** 同じAPK・同じクライアントIDのままで、各自が自分の Google アカウントを
  選ぶだけです（SHA-1 登録は不要）
- ただし同意画面が **テスト** のままだと、コンソールで「テストユーザー」に追加した
  アカウント（最大100）しかログインできません。**本番（In production）** にすると
  誰でもログインできます（制限付きスコープのため未審査の間は警告画面が出ます）
- **テスト** 状態のリフレッシュトークンは7日で失効します。常用するなら本番に切り替えてください

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
