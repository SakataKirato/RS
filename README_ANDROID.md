# Tempo Feedback Android

Android Studio で開いて使う最小アプリです。Arduino から `raw,filtered,bpm` を `115200` baud で送っている前提で、3 列目の `bpm` を読み取って目標 BPM と比較し、`早いです / 遅いです` を `TextToSpeech` で発話します。

## 前提

- Android 端末が `USB-OTG` に対応していること
- Arduino 側で `Serial.begin(115200);` を使っていること
- Arduino の出力形式が `raw,filtered,bpm` であること

## 開き方

1. Android Studio でこのフォルダを開く
2. Gradle Sync を実行する
3. Android 端末を接続して実行する

## 使い方

1. スマホと Arduino を `USB-OTG` で接続する
2. アプリで `接続` を押す
3. USB 権限を許可する
4. BPM が流れ始めたら、目標との差分に応じて音声が出る

## 実装メモ

- シリアル受信: `usb-serial-for-android`
- 音声: Android `TextToSpeech`
- 許容範囲内は発話せず、表示のみ更新
