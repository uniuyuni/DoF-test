# DoF Test — 被写界深度計算アプリ

A depth of field (DoF) calculator for Android, designed for photographers who want precise control over focus and optical characteristics.

---

## 概要 / Overview

DoF Test は、カメラの各種設定から**被写界深度（DoF）**をリアルタイムで計算・可視化する Android アプリです。センサーサイズ・焦点距離・絞り・撮影距離を組み合わせた光学計算を直感的な UI で提供します。

---

## 機能 / Features

### 被写界深度計算
- 近景限界・遠景限界・超焦点距離を光学公式で算出
- **CoC モード切替**:
  - **ピクセルモード**: センサー画素ピッチベース（センサー依存）
  - **プリントモード**: 標準 0.03mm 固定（レンズ評価基準と同等）
- **エアリーディスク考慮**（回折ぼけ）: `2.44 × 波長 × F値` で補正

### センサー対応
| センサー | サイズ |
|---------|--------|
| 1インチ | 13.2 × 8.8 mm |
| マイクロフォーサーズ | 17.3 × 13.0 mm |
| APS-C | 23.6 × 15.6 mm |
| Canon APS-C | 22.2 × 14.8 mm |
| フルフレーム | 36.0 × 24.0 mm |
| 大判 | 56.0 × 41.5 mm |

### パラメータ調整
- 焦点距離: 1〜135mm（スライダー）
- 絞り: f/1.0〜f/32（11段階）
- 撮影距離: 0.1m〜超焦点距離（スライダー）
- 画素数: 自由入力

### インタラクティブ可視化
- Canvas ベースの DoF ダイアグラム
- ドラッグで撮影距離をリアルタイム操作
- カラーコード: 青=合焦点 / 緑=DoF 範囲 / オレンジ=超焦点距離

### プリセット管理
- 任意の名前でセッションを保存・ロード・削除
- 最新 5 件を一覧表示
- SharedPreferences + JSON で永続化

### 多言語対応 / Language Support
- 日本語（デフォルト）・英語
- アプリ内の言語切替ボタンで即時反映

### セッション復元
- アプリ再起動時に前回の設定を自動復元

---

## スクリーンショット / Screenshots

> _(スクリーンショットは `screenshots/` フォルダに追加してください)_

---

## 技術スタック / Tech Stack

| 区分 | 内容 |
|------|------|
| 言語 | Kotlin 2.2.10 |
| UI | Jetpack Compose + Material Design 3 |
| テーマ | Material You (Dynamic Color) |
| 最小 SDK | API 24 (Android 7.0) |
| ターゲット SDK | API 36 (Android 15) |
| ビルド | Gradle KTS / AGP 9.2.1 |
| テスト | JUnit 4 |

**主な依存ライブラリ:**
- `androidx.compose (BOM 2024.09.00)` — UI 全般
- `androidx.activity:activity-compose:1.8.0` — Compose エントリーポイント
- `androidx.appcompat:appcompat:1.6.1` — ロケール切替サポート
- `material-icons-extended` — アイコン

---

## アーキテクチャ / Architecture

```
com.uniuyuni.doftest/
├── MainActivity.kt          # アプリ全体（UI + ロジック、約1,300行）
└── ui/theme/
    ├── Color.kt
    ├── Theme.kt             # Material You ダイナミックテーマ
    └── Type.kt
```

### 主要コンポーネント

| コンポーネント | 役割 |
|----------------|------|
| `DofCalculator` | 光学計算エンジン（object） |
| `PresetRepository` | SharedPreferences を使ったプリセット永続化 |
| `DofCalculatorApp` | ルート Composable（言語セレクター含む AppBar） |
| `InputCard` | センサー・パラメータ入力パネル |
| `VisualizationCard` | DoF ダイアグラム + 凡例 |
| `SummaryCard` | CoC・超焦点距離・近景/遠景の表示 |
| `PresetCard` | プリセット保存・ロード |
| `DofDiagram` | インタラクティブ Canvas（タッチ対応） |

### 計算式

```
超焦点距離 H = (f² / (a × CoC)) + f
近景限界   = (H × S) / (H + (S - f))
遠景限界   = (H × S) / (H - (S - f))   [H ≤ S の場合は ∞]
```

---

## ビルド & 実行 / Build & Run

### 要件
- Android Studio Hedgehog 以降
- JDK 11
- Android SDK (API 24〜36)

### 手順

```bash
# リポジトリをクローン
git clone <repo-url>
cd DoFtest

# ビルド（デバッグ）
./gradlew assembleDebug

# テスト実行
./gradlew test

# デバイスにインストール
./gradlew installDebug
```

---

## テスト / Tests

`DofCalculatorTest.kt` に 6 つのユニットテストを実装:

| テスト | 内容 |
|--------|------|
| `closeFocusFiniteRange` | 近距離での有限範囲を検証 |
| `nikon58mmF14At1m` | Nikon 58mm f/1.4 @ 1m リファレンス値との一致 |
| `zeiss29mmF152At1m` | Zeiss 29mm f/1.52 @ 1m リファレンス値との一致 |
| `printModeConsistency` | プリントモードがセンサー非依存であることを確認 |
| `pixelModeSensorDependency` | ピクセルモードがセンサー依存であることを確認 |
| `airyDiskPreference` | CoC より大きい場合エアリーディスクが採用されることを確認 |

---

## アプリ情報 / App Info

| 項目 | 値 |
|------|----|
| パッケージ名 | `com.uniuyuni.doftest` |
| バージョン | 1.6 (versionCode: 5) |
| ライセンス | — |

---

## 作者 / Author

**uniuyuni**
