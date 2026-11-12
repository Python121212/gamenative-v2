# 同梱 ReShade エフェクト

アプリが同梱しているエフェクト一式です。gamescope には ReShade のコンパイラのみが入っているため、
これらが無いと選択できるエフェクトがありません。

## 構成

```
all_shaders.json      QuickMenu が提供する全エフェクト: {path, name}（name 順）
gamenative/           パック: エフェクト本体と、それらが include する ReShade.fxh
```

各エントリの `path` は gamescope の `Shaders` ディレクトリからの相対パスです。つまり
`gamenative/Vivid.fx` は `<files>/reshade/Shaders/gamenative/Vivid.fx` に解決されます。
`name` は QuickMenu に表示される名前です。

## gamescope への反映方法

gamescope を起動するたびに、ランチャーはこのディレクトリから `<files>/reshade` を再構築し
（サブディレクトリごとに `Shaders/<pack>`、マニフェストはルート）、コンテナのホームに
`~/.local/share/gamescope/reshade` としてシンボリックリンクします。gamescope はここを参照します。
再構築は毎回無条件に行われるため、**APK が唯一の正**です。ここから削除したファイルは、
次回起動時に端末上から消えます。

エフェクトの `#include` はまず自身のディレクトリ、次に `Shaders/` を基準に解決されます。
`ReShade.fxh` がパック内に入っているのはこのためです。

## エフェクトの追加方法

`.fx` をパックディレクトリに置き、`all_shaders.json` に `{path, name}` を追加するだけです。
コードの変更は不要です。画像を読み込むエフェクトは
`texture <name> { source = "<file>"; };` を宣言し、ファイルは `Textures/` 配下に置きます
（ディレクトリは必要に応じて作成されます）。

## 制限

- gamescope の ReShade 対応は ReShade 5 のサブセットです。単一パスの色調整系は安全ですが、
  レンダーターゲットを使うマルチパスやコンピュートパスは保証されません。
- この移植版では深度バッファが利用できません（`bufready_depth` は常に false）。そのため深度を
  サンプリングするエフェクト（SSAO、被写界深度、深度ベースのアンチエイリアスなど）は動作しません。
- 同時に有効化できるエフェクトは 1 つです。QuickMenu の行はラジオボタンのように動作します。

## 出典

`gamenative/ReShade.fxh` は crosire/reshade-shaders の CC0-1.0 で、SPDX ヘッダーは保持しています。
`gamenative/` 内のエフェクトは GameNative 向けに作成したものです。
