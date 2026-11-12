<div align="center">

# GameNative

**Steam・Epic・GOG で既に所有している PC ゲームを、クラウドセーブ付きで Android 端末で遊べます。**

<a href="https://trendshift.io/repositories/14497" target="_blank"><img src="https://trendshift.io/api/badge/repositories/14497" alt="utkarshdalal%2FGameNative | Trendshift" style="width: 250px; height: 55px;" width="250" height="55"/></a>

<a href="https://www.star-history.com/utkarshdalal/gamenative">
 <picture>
  <source media="(prefers-color-scheme: dark)" srcset="https://api.star-history.com/badge?repo=utkarshdalal/GameNative&theme=dark" />
  <source media="(prefers-color-scheme: light)" srcset="https://api.star-history.com/badge?repo=utkarshdalal/GameNative" />
  <img alt="Star History Rank" src="https://api.star-history.com/badge?repo=utkarshdalal/GameNative" />
 </picture>
</a>

[![GitHub Release](https://img.shields.io/github/v/release/utkarshdalal/GameNative?style=flat-square&logo=github&label=latest)](https://github.com/utkarshdalal/GameNative/releases/latest)
[![GitHub stars](https://img.shields.io/github/stars/utkarshdalal/GameNative?style=flat-square&logo=github&color=ffd700)](https://github.com/utkarshdalal/GameNative/stargazers)
[![Discord](https://img.shields.io/badge/dynamic/json?url=https%3A%2F%2Fdiscord.com%2Fapi%2Fv9%2Finvites%2F2hKv4VfZfE%3Fwith_counts%3Dtrue&query=%24.approximate_member_count&style=flat-square&logo=discord&logoColor=white&label=discord&color=5865F2&suffix=%20members)](https://discord.gg/2hKv4VfZfE)
[![License](https://img.shields.io/badge/license-GPL%203.0-blue?style=flat-square)](https://github.com/utkarshdalal/GameNative/blob/master/LICENSE)
[![Ko-fi](https://img.shields.io/badge/ko--fi-support-FF5E5B?style=flat-square&logo=ko-fi&logoColor=white)](https://ko-fi.com/gamenative)

[**ダウンロード**](https://github.com/utkarshdalal/GameNative/releases/download/v1.4.0/gamenative-v1.4.0.apk) · [**Discord**](https://discord.gg/2hKv4VfZfE) · [**Ko-fi で支援**](https://ko-fi.com/gamenative)

<video src="https://github.com/user-attachments/assets/95b5397b-908a-44ef-a10a-dac7723580b0" autoplay loop muted playsinline width="100%"></video>

</div>

---

GameNative は、Steam・Epic・GOG のライブラリにある PC ゲームを **ストリーミングなしで** Android 上で直接実行できるアプリです。セーブデータはクラウドに同期されるので、PC で中断してスマートフォンで続きを遊ぶ、といった使い方ができます。

まだ発展途上のプロジェクトです。すべてのゲームが動くわけではなく、快適に遊ぶには調整が必要なものもあります。ただしコミュニティが動作する設定を日々見つけて共有しており、それらは自動的に適用されます。自分のゲームを誰かが動かせたかどうかは https://gamenative.app/compatibility で確認できます。

## できること

- Steam・Epic・GOG・Amazon で実際に所有しているゲームを遊べる
- PC とスマートフォンの間で引き継げるクラウドセーブ
- 既知の設定を自動適用するため、多くのゲームは調整なしである程度動く
- コントローラーとタッチ操作の両対応（専用のコントロールエディタとオンスクリーン HUD 付き）
- Steam の DLC・ワークショップ・ブランチに対応
- ゲームが動かないときは Discord でサポートを受けられる

## デモ

[TechDweeb](https://www.youtube.com/@TechDweeb) が、Android ハンドヘルドへの GameNative のセットアップを数分で紹介しています:

<div align="center">

<a href="https://youtu.be/QqIChmAu2_A?si=Ha6xzTQXZA2H8HUN&t=53" target="_blank"><img src="https://github.com/user-attachments/assets/6957e3a1-34ac-41f5-b558-0f1868dbf3d4" alt="Youtube Video" /></a>

</div>

## 使い方

1. [こちら](https://downloads.gamenative.app/releases/1.4.0/gamenative-v1.4.0.apk) から最新リリースをダウンロード
2. Android 端末に APK をインストール
3. Steam アカウントでログイン
4. ゲームをインストール
5. プレイ開始

## サポート

最も早いのは [Discord サーバー](https://discord.gg/2hKv4VfZfE) です。35,000 人以上のメンバーがいて、たいてい誰かが対応しています。

GitHub の Issue は **作成しないでください**（自動的にクローズされます）。Discord で質問してください。

プロジェクトを支援したい場合は [Ko-fi](https://ko-fi.com/gamenative) からお願いします。

## コントリビューション

手伝ってくれる方は、[Discord](https://discord.gg/2hKv4VfZfE) で連絡して **#development** チャンネルに入るか、そこでスレッドを立ててください。現在募集している作業は [Trello ボード](https://trello.com/b/vGRkFoAM/open-source-board) にあります。

### 動かないゲームを修正する

毎晩、実際の利用データから「GameNative で動かないゲーム」の一覧を [GameNative/gamenative-compat](https://github.com/GameNative/gamenative-compat) に公開しています。動かないゲームにはそれぞれ [Issue](https://github.com/GameNative/gamenative-compat/issues?q=is%3Aopen+label%3Adoes-not-work) があり、何人が遭遇したか、どんな報告があるか、ゲーム画面が描画されたことがあるか、といった数値が載っています。

取り組む場合は次のとおりです:

1. 一覧からゲームを選んで Issue を開きます。コメントで誰かが既に対応していないか確認し、担当する旨をコメントします。
2. 失敗の原因を突き止めます。修正の多くはこのリポジトリ、[proton-wine](https://github.com/GameNative/proton-wine)、[FEX](https://github.com/GameNative/FEX)、GPU ドライバのラッパー、またはゲームごとの設定で行います。
3. 適切なリポジトリでプルリクエストを作成し、そのリンクをゲームの Issue にコメントします。修正が配信されてプレイヤーが動かせるようになると、Issue は自動で閉じられます。

動かないゲームを所有していて、修正してくれる人にキーを提供したい場合は [support@gamenative.app](mailto:support@gamenative.app) までメールしてください。

### ビルド

多くの場合、これは必要ありません。遊ぶだけなら上のリリースを使ってください。ここはコントリビューター向けの内容です。

1. 通常の Android Studio プロジェクトと同様にビルドします。詰まったら Discord で聞いてください。
2. **SteamGridDB API キー（任意）:** カスタムゲームのアートワークを取得するには、`local.properties` にキーを追加します:
   ```properties
   STEAMGRIDDB_API_KEY=your_api_key_here
   ```
   キーは [SteamGridDB の設定](https://www.steamgriddb.com/profile/preferences) から取得できます。無くても動作しますが、画像の取得は行われません。

## 解析とプライバシー

GameNative は匿名の解析のために [PostHog](https://posthog.com) を利用しています。氏名・メールアドレス・IP アドレス・端末識別子などの個人情報は一切収集しません。

**常に収集される情報**（ゲーム互換性の改善のため）:
- ゲームの起動・終了・離脱イベント（ゲーム名、ストア、プレイ時間、平均 FPS、コンテナ設定）
- ゲームのインストール・キャンセル・アンインストール イベント

これにより、どのゲームが動くのか、どの程度快適に動くのか、次に使う人へどの設定を自動適用すべきかを判断しています。個人を特定できる情報ではありません。

**任意**で、*設定 → 情報 → 利用状況の解析* から切り替えできます:
- 機能の利用状況（オンスクリーンキーボード、コントローラー、HUD、コントロールエディタ）
- ログインの成功／失敗イベント
- レコメンドの操作
- アプリのライフサイクル（前面／背面）
- クラウド同期イベント

詳細は [プライバシーポリシー](PrivacyPolicy/README.md) を参照してください。

## 支援者

[Ko-fi スポンサー](https://ko-fi.com/gamenative) と [GitHub スポンサー](https://github.com/sponsors/utkarshdalal?preview=true)、そして [CodeRabbit](https://coderabbit.link/gnative) に感謝します。

[![Star History Chart](https://star-history.dera.page/svg?repos=utkarshdalal/GameNative&type=Date&theme=dark)](https://star-history.dera.page/#utkarshdalal/GameNative&Date)

## ライセンス

[GPL 3.0](https://github.com/utkarshdalal/GameNative/blob/master/LICENSE)。

サードパーティの帰属表示、コピーレフトのソース提供、および同梱されているサードパーティ／プロプライエタリ製コンポーネントに関する告知は [THIRD_PARTY_NOTICES](THIRD_PARTY_NOTICES) を参照してください。

---

**免責事項:** 本ソフトウェアは、あなたが合法的に所有しているゲームを遊ぶためのものです。海賊版やその他の違法な目的には使用しないでください。メンテナーは不正使用について一切の責任を負いません。
