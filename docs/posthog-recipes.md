# PostHog レシピ集

クライアント側にトラッキングを追加する代わりに、サーバー側（PostHog）で導出しているクエリ集です。
いずれも「利用状況の解析」設定の背後で既に送信されているイベントのみを使います。

## ストアをクリックした後、どれだけ離れていたか

アプリは「ユーザーがストアページから戻ってきた時刻」を記録していません。クリックイベントと、
同じ人物の次の `Application Opened` イベントから導出します。これは PostHog SDK 自身の
ライフサイクルイベントで、前面に戻るたびに発火します。独自の `app_foregrounded` は使わないでください
（2026年9月時点では PostHog にまったく現れません）。Discover の購入ボタンには
`recommendation_link_clicked`、キャンペーンの CTA には `featured_action_clicked` を使います。
どちらも `sid`（CJ のサブID）、`source`、`rank` を持っています。

```sql
SELECT sid, game_name, source, rank, click_ts, next_fg,
       dateDiff('second', click_ts, next_fg) AS away_s
FROM (
    SELECT event, timestamp AS click_ts,
           properties.sid AS sid,
           properties.game_name AS game_name,
           properties.source AS source,
           properties.rank AS rank,
           -- first foreground after this row, per person
           minIf(timestamp, event = 'Application Opened') OVER (
               PARTITION BY person_id ORDER BY timestamp
               ROWS BETWEEN 1 FOLLOWING AND UNBOUNDED FOLLOWING
           ) AS next_fg
    FROM events
    WHERE timestamp >= now() - INTERVAL 7 DAY
      AND event IN ('recommendation_link_clicked', 'Application Opened')
)
WHERE event = 'recommendation_link_clicked'
  AND next_fg > click_ts                          -- minIf returns epoch 0 when nothing follows
  AND next_fg <= click_ts + INTERVAL 30 MINUTE    -- longer gaps are a new session, not a return
ORDER BY click_ts DESC
```

補足:

- `events` のスキャンは 1 回で、結合はありません。ウィンドウ関数の `minIf` で人物ごとの次の前面化を求めます。
- 30 分以内に前面化がなかったクリックは除外されます。「戻ってこなかった」率を出したい場合は別途集計してください:
  同じクエリから `next_fg` に関する 2 つの条件を外し、`countIf(next_fg = 0 OR
  next_fg > click_ts + INTERVAL 30 MINUTE)` を取ります。
- `source` が `hero` で `rank` が `-1` のものは、Discover ではなく「すべて」タブのヒーローカードです。

## CJ の売上と掲載位置を結びつける

GOG のアフィリエイトリンクのサブIDは `gn_{source}_r{rank}_{clickId}` です。`clickId` は
利用状況の解析がオンの場合にのみ含まれます。CJ の手数料レポートでは、SID を `_` で分割して
source と rank を得て、`clickId` を `recommendation_link_clicked` または `featured_action_clicked` の
`properties.click_id` と突き合わせれば、クリックの残りのプロパティが分かります。

## キャンペーンからインストール／起動へ

`game_install_started` と `game_launched` は、直近 14 日以内にキャンペーンの CTA 経由で
Steam のアプリページに到達した場合、`campaign_id`、`campaign_source`、`campaign_click_age_s` を持ちます。
導出は不要で、`campaign_id` が設定されているものだけを絞り込めば十分です。
