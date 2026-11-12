# OpenAL Soft — オーディオ DLL の上書き

`openal.tzst` には OpenAL Soft v1.25.1 の DLL が入っており、Mirror's Edge や一部の Unreal Engine 3
タイトル、OpenAL に依存する GOG 版などに OpenAL オーディオ機能を提供します。

## 仕組み

OpenAL は（設定インポートを壊さないために）**Win Components の仕組みには含めていません**。
代わりに、ユーザーがコンテナの「環境変数」タブから標準の `WINEDLLOVERRIDES` 環境変数を追加します。

### よく使うプリセット（ドロップダウンから選択可能）

| WINEDLLOVERRIDES の値                     | 効果                                                       |
|-------------------------------------------|------------------------------------------------------------|
| `openal32=native,builtin`                 | DLL を展開し、Wine はネイティブの openal32 を優先            |
| `soft_oal=native`                         | DLL を展開し、Wine はネイティブの soft_oal を使用            |
| `openal32=native,builtin;soft_oal=native` | DLL を展開し、両方の上書きを同時に適用                       |
| *（未設定）*                               | 上書きなし — Wine 内蔵のものを使用（既定）                   |

作成ダイアログで任意の `WINEDLLOVERRIDES` を直接入力することもできます。

起動時、アプリは次の処理を行います:
1. コンテナの環境変数から `WINEDLLOVERRIDES` を読み取る
2. 値に `openal32` または `soft_oal` が含まれていれば、`openal.tzst` を `drive_c/windows/` に展開する
3. `WINEDLLOVERRIDES` をそのまま Wine に渡す（変換は不要）

### 推奨設定

多くのゲームでは `openal32=native,builtin` を使ってください。これにより `openal32.dll`
（OpenAL の標準的なシステム名）と `soft_oal.dll`（再配布用の名前）の両方が `system32/` と
`syswow64/` に配置され、Wine はネイティブ DLL を優先するようになります。

## アーカイブの中身

```
./system32/openal32.dll    ← 64bit OpenAL Soft（システム名 openal32 として）
./system32/soft_oal.dll    ← 64bit OpenAL Soft（再配布時の元の名前）
./syswow64/openal32.dll    ← 32bit OpenAL Soft（システム名 openal32 として）
./syswow64/soft_oal.dll    ← 32bit OpenAL Soft（再配布時の元の名前）
```

両方の名前を同梱しています。多くのゲームは標準 API 経由で `openal32.dll` を読み込みますが、
一部のタイトル（GOG 版や古い UE3 ゲーム）は `soft_oal.dll` を直接読み込みます。

## openal.tzst の再ビルド方法

1. https://openal-soft.org/ から OpenAL Soft（最新リリース）をダウンロードします
   - Win32 と Win64 の両方の `soft_oal.dll` を含む **bin** zip が必要です

2. ステージングディレクトリを作成します:
   ```
   system32/openal32.dll    (Win64 soft_oal.dll をコピーしてリネーム)
   system32/soft_oal.dll    (Win64 soft_oal.dll を元の名前で)
   syswow64/openal32.dll    (Win32 soft_oal.dll をコピーしてリネーム)
   syswow64/soft_oal.dll    (Win32 soft_oal.dll を元の名前で)
   ```

3. アーカイブを作成します:
   ```bash
   cd staging_dir
   tar -cf openal.tar ./system32 ./syswow64
   zstd openal.tar -o openal.tzst
   ```

4. `openal.tzst` を `app/src/main/assets/wincomponents/openal.tzst` に配置します

## OpenAL インストーラを同梱するゲーム

`oalinst.exe` は決して実行されません。ゲームが同梱している場合（`_CommonRedist/OpenAL`、`redist/` など）、
`OpenALRedist.applyForLaunch` がコンテナの `WINEDLLOVERRIDES` に `openal32=native,builtin` を追加します。
ただし `openal32` または `soft_oal` の上書きが既に設定されている場合は追加せず、上表から選んだ
ユーザー自身の設定が尊重されます。いずれの場合も、起動時にアーカイブは展開されます。
