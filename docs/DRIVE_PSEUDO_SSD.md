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
| `service/drive/DriveAuthManager.kt` | Google サインイン（複数アカウント）と Drive サービス生成 |
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

## 事前準備（Google Cloud / OAuth）

この機能はアプリ側の OAuth クライアント設定が無いとサインインできません。

1. Google Cloud Console でプロジェクトを作成
2. **Google Drive API** を有効化
3. **OAuth 同意画面** を設定し、スコープ `.../auth/drive.file` を追加
   - `drive.file` は「制限付きスコープ」なので、公開前に Google の審査が必要です。
     審査前は *テストユーザー* に追加したアカウントのみサインインできます。
4. **認証情報 → OAuth クライアント ID → Android** を作成
   - パッケージ名: `app.gamenative`
   - SHA-1: 署名に使うキーストアのフィンガープリント
     （`keytool -list -v -keystore <keystore> -alias <alias>`）
5. アプリを再ビルドしてインストール

> 注意: デバッグ署名とリリース署名では SHA-1 が異なります。両方のクライアント ID を
> 登録するか、使用する署名のものを登録してください。

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
