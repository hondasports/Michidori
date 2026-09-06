# Michidori Agent Loop

Michidori の開発ループは `hondasports/kakeibo` の Agent Loop をベースにしつつ、Android / Emulator / Pixel 実機開発向けに高速化している。

## Default path

```text
PREPARE → IMPLEMENT → VERIFY → REVIEW? → COMMIT & PUSH → DONE
```

通常は `main` を直接更新する。task branch / PR はデフォルトでは作らない。

## What was inherited

- Spec Confidence と Risk の分離
- AC / IV / TC による compact contract
- forward / reverse coverage
- shared diff writer は原則1体
- same-content Evidence の再利用
- Process Learning は event-driven
- Human Gate は具体的な危険操作の直前だけ
- stage間で全文を再要約せず task-state を引き継ぐ

## Michidori adaptation

Android開発に合わせて次を標準化する。

```text
compile/static
  ↓
unit/contract
  ↓
Android Emulator
  ↓
GPS / sensor injection
  ↓
Pixel 10 Pro targeted verification
  ↓
prolonged device run when needed
  ↓
small logical commit to main
```

## Commit-first delivery

開発速度を優先し、PRではなく小さなcommitをレビュー単位にする。

- `main` へ直接 commit / push する
- 1 commit = 1 logical change を基本にする
- required Verification が通った単位だけ push する
- unrelated change を同じcommitへ混ぜない
- 大きなtaskは検証可能な縦切りへ分割する
- broken intermediate state は `main` に置かない
- PRは明示依頼、direct main禁止、外部レビュー必須の場合だけ使う

## Emulator-first

以下は基本的に Emulator で閉じる。

- UI
- state machine
- ring buffer
- persistence
- event logic
- GPS/IMU injection
- lifecycle
- failure handling

## Pixel-only / Pixel-first

以下は実機 Evidence が必要。

- 4K30 / 1080p60 の安定性
- codec / camera hardware
- thermal / battery
- actual GPS / IMU
- prolonged recording
- ARCore Depth
- ML performance
- Gemini Nano / AICore

## Context model

PREPARE 後は `.loop/state/<task-id>.yaml` に compact contract を持つ。

長い issue / chat / source 本文は毎stage再読しない。新しい矛盾・scope変更・hardware dependency が出た時だけ該当 source を再確認する。

## Completion philosophy

「全部のテストを毎回回した」ではなく、**今回の AC / IV を必要十分な Evidence で証明し、その論理変更を `main` へ安全に反映した**ことを完了条件にする。
