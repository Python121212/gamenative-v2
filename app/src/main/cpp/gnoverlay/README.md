# libgnoverlay

`libgnoverlay.so` は、Wine プレフィックスに対する `LD_PRELOAD` 方式のコピーオンライト・オーバーレイです。
ゲームごとの「upper」プレフィックスを、共有された読み取り専用の「lower」ベースプレフィックス
（Proton バージョンごとに 1 つ）の上に重ねます。読み込みは lower にフォールスルーし、書き込みは
ファイルを upper へコピーアップし、削除は upper にホワイトアウトとして記録され、ディレクトリ一覧は
両方をマージします。Wine 自体は改変しません。

## 環境変数の取り決め（アプリが設定）

| 変数 | 意味 |
|---|---|
| `GN_OVERLAY_UPPER` | ゲームごとの upper レイヤープレフィックスのホスト上の絶対パス。例: `/data/user/0/app.gamenative/files/imagefs_shared/home/xuser-STEAM_204360/.wine` |
| `GN_OVERLAY_LOWER` | ベースプレフィックスのホスト上の絶対パス。例: `/data/user/0/app.gamenative/files/imagefs_shared/proton/proton-10.0-4-x86_64/base_prefix/.wine` |
| `GN_OVERLAY_ALIASES` | コロン区切りの追加の絶対プレフィックス。UPPER を指す別表記（シンボリックリンク経由の表記や、ゲスト側の `/home/xuser/.wine` など） |
| `GN_OVERLAY_HIDE` | コロン区切りのエントリ名。オーバーレイかどうかに関わらず、すべてのディレクトリ一覧から除外する（アプリは自身の `.DepotDownloader:.DownloadInfo` メタデータディレクトリを渡す）。パス指定によるアクセスは可能なまま |
| `GN_OVERLAY_DEBUG=1` | stderr と logcat（タグ `gnoverlay`）に詳細ログを出力。stdout には決して出さない |
| `GN_OVERLAY_LOG` | デバッグ時、ログをこのファイルにも追記する |

パスは正規化したうえで、UPPER・エイリアス・LOWER のいずれかに一致するか、それらの直後に `/` を
続けた形で始まる場合にオーバーレイ内と判定します。読み取り専用オープンや `chdir` の後は、カーネルが
fd や cwd（`/proc/self/fd`、`getcwd`）に対して lower 側のパスを返すため、LOWER も一致対象です。
照合前に `/data/data/` は `/data/user/0/` に書き換えられるので、環境変数は `/data/user/0` 形式で
指定する必要があります。

UPPER か LOWER が未設定・相対パス・ディレクトリでない、または両者が重なっている場合、ライブラリは
1 行ログを出し（どちらかの変数が設定されているかデバッグがオンのとき）、すべてのフックは素通りします。

## ディスク上の形式

- ホワイトアウト: 空の通常ファイル `<UPPER>/.gnoverlay/wh/<key>`。lower に存在していても、そのパスは
  存在しない扱いになります。ディレクトリに対するホワイトアウトは lower のサブツリー全体を隠します。
  パスが upper に再作成されると削除されます。
- 不透明ディレクトリ: 空の通常ファイル `<UPPER>/.gnoverlay/opaque/<key>`。upper のディレクトリが
  lower を完全に覆い隠し、その配下の解決と一覧は upper のみを対象にします。ディレクトリを削除して
  再作成した場合や、lower に存在するパスへディレクトリをリネームした場合に書き込まれます。
- `<key>` はオーバーレイからの相対パスの正規形です。親ディレクトリはカーネルが解決し（`O_PATH` で
  オープンして `/proc/self/fd` 経由）、最後の要素名は未解決のまま付加します。そのため
  `dosdevices/c:/windows/x.dll` と `drive_c/windows/x.dll` は同じマーカーを共有します。親が
  オーバーレイ外に解決される場合（`dosdevices/z:` 経由など）はマーカーを作りません。
- コピーアップ用の一時ファイル: `<upper のパス>.<pid>.<seq>.gnoverlay-tmp`。コピーごとに一意です。
  置換せずに公開します: `link(tmp, upper)` の後に `unlink(tmp)`、`link` が拒否された場合は
  `renameat2(RENAME_NOREPLACE)`。upper のファイルが既にある場合（別プロセスが先にコピーアップした
  場合）は、一時ファイルを破棄して既存の upper ファイルを使います。一時ファイルは失敗時に削除され、
  一覧からも隠されます。
- `.gnoverlay` は UPPER のルート一覧から隠され、エイリアス経由では `ENOENT` を返します。UPPER の
  実際の表記で直接アクセスした場合はそのまま通過します。

## セマンティクス

- パスは絶対パス化され（cwd、または `*at()` 系の dirfd なら `readlink(/proc/self/fd/N)`）、`//`、`.`、
  `..` はテキスト的に畳み込まれます。すべてのスキャンは入力長で上限が決まります。途中のシンボリック
  リンクはカーネルが解決します。解決は `<UPPER>/<rel>` を `lstat` し、次に `<LOWER>/<rel>` を試します。
  これは upper と lower が同じ `dosdevices` リンクを持つことを前提にしています。
- 最終要素のシンボリックリンクは、リンクを辿る呼び出し（`stat`、`O_NOFOLLOW` なしの `open`、
  `opendir`、`chdir`、`chmod` など）ではマージ後のビューで最大 8 ホップまで辿ります。これにより、
  リンク先が lower にのみ存在する upper のリンクも機能します。オーバーレイ外のターゲットは
  そのまま実際の呼び出しに渡されます。
- 解決順: upper が勝ちます。upper の `ENOTDIR`（ディレクトリ以外がパスを覆っている）は「存在しない」
  とみなします。そうでなければ、そのパスまたは祖先のホワイトアウト、あるいは祖先の不透明マーカーが
  lower を隠します。それも無ければ lower を使います。
- 読み取り専用の呼び出し（書き込み意図なしの `open`、`fopen "r"`、`stat`/`lstat`/`fstatat`/`statx`、
  `access`/`faccessat`、`readlink`/`readlinkat`、`getxattr`/`listxattr` とその `l` 版、`chdir`）は、
  解決後の実パスに対して実行されます。
- 書き込みを伴うオープン（`O_WRONLY`、`O_RDWR`、`O_TRUNC`、`O_APPEND`、または存在しないファイルへの
  `O_CREAT`）:
  - 存在しない upper の親は、lower を鏡写しにして作成します。ディレクトリは `mode | 0700`、lower の
    シンボリックリンク（例: `dosdevices/d:`）はリンク先を実体化したうえでシンボリックリンクとして
    再作成します。
  - lower のファイルはコピーアップされます。データは `O_TRUNC` で読み飛ばし、モードは `| 0600`、
    atime/mtime と `user.*` xattr は保持します。
  - そのうえで upper を呼び出し元のフラグでオープンし、オープン成功後にホワイトアウトを削除します。
  - `O_CREAT|O_EXCL` は、マージ後のビューにファイルがあれば `EEXIST` を返します。
- その他の書き込み系呼び出し:
  - `unlink`/`unlinkat`: upper のコピーを削除し、lower にそのパスがあればホワイトアウトを書きます。
  - `rmdir`: マージ後のディレクトリが空である必要があります。
  - `mkdir`/`mkdirat`: ホワイトアウトされた lower のディレクトリに対しては、不透明マーカーを書きます。
  - `rename`/`renameat`/`renameat2`: `RENAME_NOREPLACE` と `RENAME_EXCHANGE` に対応します。
    ディレクトリはまず再帰的にコピーアップされます。
  - `symlink`/`symlinkat`、`link`/`linkat`: 境界をまたぐリネームやリンクはコピーアップしてから upper に対して実行します。
- `chmod`/`fchmodat`/`utimensat`/`truncate`/`setxattr`/`lsetxattr`/`removexattr`/`lremovexattr` は
  先にコピーアップします。bionic では `utimes`、`utime`、`lutimes`、`futimens` はすべて
  `utimensat@plt` を経由するため、これらも対象になります。
- LOWER を指す fd（`readlink(/proc/self/fd/N)` で判定）に対する `fchmod`/`fsetxattr`/`fremovexattr` と
  `utimensat(fd, NULL, ...)` は、ファイルをコピーアップして upper のパスに変更を適用します。呼び出し元の
  fd は lower のファイルを読み続けます。
- ディレクトリ一覧: オーバーレイ内ディレクトリの `opendir`/`fdopendir` は、マージ・重複排除済みの
  スナップショット（upper のエントリ、その後に隠されていない・ホワイトアウトされていない lower の
  エントリ。不透明の場合は upper のみ）を保持する内部タグ付きオブジェクトを返します。`readdir`、
  `readdir64`、`readdir_r`、`readdir64_r`、`rewinddir`（再スナップショット）、`telldir`、`seekdir`、
  `closedir`、`dirfd`、`scandir` がこれを理解し、通常の `DIR*` はそのまま通過します。Wine の ntdll は
  `opendir`/`readdir` で、wineserver は `fdopendir`/`readdir` でディレクトリを読みます。生の
  `getdents64` は横取りしません。`GN_OVERLAY_HIDE` に挙げた名前は、実際の `DIR*` に対する `readdir`
  系でもスキップされるため、インストールディレクトリを走査するゲームがアプリのメタデータディレクトリを
  見ることはありません（Alan Wake のパックスキャンが、これらが原因で 32 スロットの文字列リングを
  オーバーランしていました）。
- `realpath` は、lower にあるファイルであっても upper 表記の正規パスを返します。

## エクスポートするフック（62 個）

`open open64 openat openat64 __open_2 __openat_2 creat fopen fopen64 stat lstat fstatat
fstatat64 statx access faccessat readlink readlinkat __readlink_chk __readlinkat_chk getxattr
lgetxattr listxattr llistxattr setxattr lsetxattr removexattr lremovexattr fsetxattr
fremovexattr fchmod chmod fchmodat utimensat truncate unlink unlinkat rmdir mkdir mkdirat rename
renameat renameat2 symlink symlinkat link linkat opendir fdopendir readdir readdir64 readdir_r
readdir64_r rewinddir telldir seekdir closedir dirfd scandir chdir realpath dlsym`

## 割り込みの仕組み（bionic）

単純なシンボル割り込みです。ライブラリは `LD_PRELOAD` によってグローバルなルックアップグループに入る
ため、wine の実行ファイルと、それが後から `dlopen` するすべてのライブラリ（`ntdll.so`、
`win32u.so` など）は、libc のインポートをこれらのフックに束縛します。

実際の関数は、`dlvsym(RTLD_NEXT, "dlsym", NULL)` で取得した本物の `dlsym` を使って `RTLD_NEXT`
経由で一度だけ解決します。

`dlsym` は box64 のためにフックしています。x86-64 の Proton は `imagefs/usr/bin/box64` 上で動作し、
実際のライブラリハンドルに対して `dlsym` を呼んでネイティブの libc 関数を得るため、そのままでは
割り込みを迂回してしまうからです。フックの動作は次のとおりです:

- 真の呼び出し元を渡してリンカの `__loader_dlsym(handle, name, caller)` に転送するので、他の
  ライブラリの `RTLD_NEXT` ルックアップは本来の意味を保ちます。これは bionic の libdl の `dlsym` と
  まったく同じ動作です。`__loader_dlsym` が見つからなければ、本物の `dlsym` にフォールバックします。
- `RTLD_NEXT` の場合は結果をそのまま返すため、割り込みの連鎖が壊れることはありません。
- それ以外では、名前がエクスポートしているフックのいずれかで、かつ解決されたアドレスがそのフックに
  対して解決した実際の libc 関数（または既に我々のフック）である場合に限り、我々のフックを返します。
  それ以外はそのまま返します。

この判定はコア内の `gno_dlsym_pick` にあり、ホスト側のテストでカバーしています。

割り込みの連鎖（`libredirect` や `libsysvshm` など他のプリロードも同じ連鎖に入ります）が再帰しない
よう、2 つのルールを守っています:

- コア内で行うファイルシステム操作はすべて生のシステムコール（aarch64 ではインラインの `svc #0`）を
  使い、libc のラッパーは使いません。bionic の `unlink` などのラッパーは `unlinkat@plt` を呼び、
  プリロードの連鎖に再突入してしまうためです。
- 各フックは自分自身の実際の関数へパススルーします（`renameat2` → 実際の `renameat2`、`rename` →
  実際の `rename`）。bionic の `rename` と `renameat` は `renameat2@plt` に末尾呼び出しします。
  `renameat2(flags=0)` を libc の `renameat` に転送するフックは、末尾呼び出しの循環で無限ループに
  なりました。これは端末上で `winedevice.exe` が fontconfig の `FcAtomicReplaceOrig` を呼ぶ経路で
  実際に発生しています。残りの相互呼び出しは直接システムコールのスタブ（`openat`、フラグ 0 の
  `fchmodat`、`setxattr`）に向けています。

## ビルド

```
./build.sh
```

NDK 27.3 の clang を直接使用し（`--target=aarch64-linux-android29 -O2 -fPIC -shared
-fvisibility=hidden -U_FORTIFY_SOURCE -D_FORTIFY_SOURCE=0`、`-ldl -llog`、
`-Wl,-z,max-page-size=16384`、ストリップあり）、`out/bionic/libgnoverlay.so` を出力してから
`app/src/main/jniLibs/arm64-v8a/libgnoverlay.so`（`libevshim.so` の隣）へコピーします。
`CMakeLists.txt` は NDK の CMake ツールチェーン経由で同じライブラリをビルドします。別の NDK を
使う場合は `ANDROID_NDK_HOME` を設定してください。

## テスト

```
./tests/run.sh
```

`gnoverlay_core.c` を ASan/UBSan 付きでネイティブにビルドし、一時ディレクトリに対して
`tests/test_core.c` を ops vtable 経由（ホスト libc、割り込みなし）で実行します。カバー範囲:

- 解決の優先順位、ホワイトアウト、不透明ディレクトリ
- コピーアップ（モード、mtime、xattr、`0444` の lower ファイル）
- リネーム、リンク、シンボリックリンク
- 両レイヤーおよび lower のみにある `dosdevices` リンクと、正規化されたマーカーキー
- マージされた一覧
- fontconfig のリネーム回帰
- 反復回数に上限を設けた 10 万パスの正規化ファズ
- エイリアス境界での `.` 要素
- `dlsym` フックの判定

スクラッチ領域は `TMPDIR` で指定できます。

## 既知の制限

- コピーアップ後は inode の同一性が変わります。ファイルの `st_dev`/`st_ino` は、初回書き込み時に
  lower から upper のものへ切り替わります。Wine と wineserver は一部の状態を dev/ino で管理しています。
- lower のファイルを読み取り専用でオープンした fd は、後でコピーアップされても lower を読み続けます。
- fd ベースの変更がコピーアップされるのは `fchmod`、`fsetxattr`、`fremovexattr`、`futimens`
  （`utimensat` 経由）のみです。`fchown`、`ftruncate`、書き込みには書き込みモードの fd が必要で、
  これは常に upper 側になります。
- upper と lower が同じパスに異なる途中のシンボリックリンクを持つ場合（ゲームが `dosdevices/d:` を
  付け替えた場合など）、パスはまず upper のリンクで解決され、次に lower のリンクで解決されるため、
  両方のターゲットが混ざった結果になり得ます。
- fd に対する生の `getdents64` でのディレクトリ読み取りは、マージ後のビューではなく物理ディレクトリ
  （upper または lower）のみを返します。
- ホワイトアウト／不透明の確認には、親を正規化するための `open`+`readlink`+`close` が余分にかかります
  が、`.gnoverlay/wh` または `.gnoverlay/opaque` が存在する場合に限られます。
- 同じファイルの同時コピーアップ（レジストリファイルに対する wine と wineserver など）は安全です。
  一方のコピーが勝ち、もう一方は破棄されるため、公開済みの upper ファイルが lower の新しいコピーで
  置き換わることはありません。ただし lower の fd を保持しているプロセスは lower を読み続けます。
- lower の読み取り専用固定ファイル（`0444`）は、コピーアップされるまで `stat`/`access` でそのモードを
  報告します。Wine は書き込みビットの欠如から `FILE_ATTRIBUTE_READONLY` を導出します。
- lower にのみ存在するファイルへの `O_RDONLY|O_CREAT` は lower をオープンし、コピーアップしません。
- lower に存在するディレクトリのリネームは、まずマージ後のツリーを upper にコピーします。
- オーバーレイ外のシンボリックリンク経由でオーバーレイに入るパスが `GN_OVERLAY_ALIASES` に無い場合、
  オーバーレイされません。
- 相対パスは、cwd がオーバーレイ外であっても呼び出しごとに `getcwd` が 1 回余分にかかります。
- フックしていないもの: `mknod`、`mkfifo`、`chown` 系、`execve`、`statfs`/`statvfs`、`freopen`、
  `scandirat`、`openat2`、`getdents64`/`syscall`、`close`/`dup*`/`lseek`/`fcntl`。
  bionic の `stat64`、`lstat64`、`creat64`、`scandir64`、`scandirat64` は、libc 内部の PLT 呼び出しを
  経由してフックに到達します。
