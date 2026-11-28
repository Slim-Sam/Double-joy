#!/usr/bin/env python3
"""
Builds the APK-exclusive assets/solver.html (v1.0.16) from Решалка_Двойная_радость_Телефон.html
WITHOUT modifying the standalone mobile HTML or Python files.

v1.0.16 upgrades:
1. Phone-first Single-Screen Layout (single_screen_sheet):
   - Compact 2-row sticky mobile header (Stage, Score/Target, Overlay, Diff Sync, Stack Editor, Undo/Redo, Log, Saves/Menu)
   - Fits Hex Board + 3 Hand Slots + Best Move Plan #1 on a single vertical phone screen without multi-column scrolling
   - Slide-up Bottom Sheet stack composer on tapping any hex cell or hand slot (with instant Slot #1/#2/#3 switcher pills)
   - Slide-up sheets for Action Log (.log export to Android Downloads) and Saves / Stage Menu (.json export to Downloads)
2. Approval-Gated Board Diff Sync (both_modes):
   - Detailed in-app Diff Confirmation & Inline Layer Tweak modal synced with CaptureOverlayService's pendingDiff
   - Highlights differing cells (#cid) directly on the SVG Hex Board until user confirms or cancels
"""
import pathlib

SRC = pathlib.Path("/home/user/double_joy_android_apk/base_ui_source.html")
DST = pathlib.Path("/home/user/double_joy_android_apk/assets/solver.html")
SUD_CREST_B64_PATH = pathlib.Path("/home/user/double_joy_android_apk/sud_crest_b64.txt")
SUD_CREST_WEBP_PATH = pathlib.Path("/home/user/double_joy_android_apk/assets/sud_crest.webp")

APK_CUSTOM_CSS = r"""
<style id="apk-ui-overrides">
  :root {
    --apk-hdr-bg: rgba(11, 17, 32, 0.97);
    --apk-sheet-bg: #111827;
  }

  html, body {
    background: #0b1120;
    width: 100% !important;
    max-width: 100vw !important;
    min-height: 100vh;
    min-height: 100dvh;
  }

  html {
    overflow-x: hidden !important;
    overscroll-behavior-y: contain;
  }

  body {
    overflow-x: hidden;
    overscroll-behavior-y: contain;
    /* Single scroll container — no flex column that creates nested scrollers */
  }

  /* Block background scroll when modal/sheet is open */
  html.apk-modal-open {
    overflow: hidden !important;
    height: 100% !important;
  }

  .apk-sheet-backdrop,
  .apk-modal-sheet {
    overscroll-behavior: contain;
    touch-action: none;
  }

  .apk-scanning {
    opacity: 0.6 !important;
    pointer-events: none !important;
    animation: apk-pulse 1.2s ease-in-out infinite;
  }

  @keyframes apk-pulse {
    0%, 100% { opacity: 0.4; }
    50% { opacity: 0.8; }
  }

  /* Hide desktop/browser-only elements */
  .brand,
  .brand-sub,
  #btnDemoSetup,
  #btnToggleImgMode,
  #btnToggleStackInfo,
  #btnRotate,
  .col-board > .mode-tabs,
  #viewScreenshot,
  #btnRandomHand,
  .col-editor > .panel-header,
  #iconCustomizerBox,
  #layersEditorList ~ div,
  #plansContainer > .plan-card:nth-child(n+2),
  .col-solver > .panel-header,
  #solverMeta,
  #forecastBox,
  #autoSaveBadge {
    display: none !important;
  }

  #viewBoard {
    display: flex !important;
    flex-direction: column !important;
    width: 100% !important;
    min-width: 0 !important;
    flex: 1 1 auto !important;
  }

  /* --- COMPACT 2-ROW PHONE STICKY HEADER (Adaptive 300px .. 720px) --- */
  .topbar {
    position: sticky !important;
    top: 0 !important;
    z-index: 700 !important;
    padding: 5px 6px 6px !important;
    background: var(--apk-hdr-bg) !important;
    border-bottom: 1px solid rgba(71, 85, 105, 0.55) !important;
    backdrop-filter: blur(10px);
    display: block !important;
    width: 100% !important;
    flex-shrink: 0;
  }

  /* Hide legacy topbar-controls visually once our compact phone header mounts */
  body.apk-phone-ready .topbar > .topbar-controls {
    display: none !important;
  }

  .apk-hdr-wrap {
    display: flex;
    flex-direction: column;
    gap: 4px;
    width: 100%;
    max-width: 680px;
    margin: 0 auto;
  }

  .apk-hdr-row1,
  .apk-hdr-row2 {
    display: flex;
    align-items: center;
    gap: 4px;
    width: 100%;
    min-width: 0;
  }

  .apk-pill-box {
    display: inline-flex;
    align-items: center;
    gap: 3px;
    background: #172136;
    border: 1px solid #334155;
    border-radius: 8px;
    padding: 2px 5px;
    height: 32px;
    min-width: 0;
  }

  .apk-pill-lbl {
    font-size: clamp(10px, 2.8vw, 11px);
    color: #94a3b8;
    font-weight: 700;
    white-space: nowrap;
  }

  @media (max-width: 372px) {
    #apkScoreBox .apk-pill-lbl,
    #apkStageBox .apk-pill-lbl {
      display: none !important;
    }
  }

  .apk-hdr-row1 select#stageSelect {
    background: transparent !important;
    border: none !important;
    color: #f8fafc !important;
    font-size: clamp(11.5px, 3.2vw, 13px) !important;
    font-weight: 800 !important;
    padding: 0 1px !important;
    min-width: 0;
    max-width: 96px;
    height: 26px;
  }

  .apk-score-box {
    flex: 1 1 84px;
    min-width: 78px;
    position: relative;
    overflow: hidden;
    justify-content: center;
  }

  .apk-score-box input#curScoreInput {
    width: clamp(40px, 12vw, 52px) !important;
    min-width: 38px !important;
    background: #0f172a !important;
    border: 1px solid #475569 !important;
    border-radius: 6px !important;
    color: #fde68a !important;
    font-size: clamp(11.8px, 3.3vw, 13.5px) !important;
    font-weight: 900 !important;
    padding: 2px 3px !important;
    text-align: center;
    height: 24px;
  }

  .apk-goal-text {
    font-size: clamp(10.5px, 3vw, 11.5px);
    font-weight: 700;
    color: #cbd5e1;
    white-space: nowrap;
  }

  .apk-mini-prog {
    position: absolute;
    left: 0;
    right: 0;
    bottom: 0;
    height: 2.5px;
    background: rgba(51, 65, 85, 0.6);
  }

  .apk-mini-prog-fill {
    height: 100%;
    width: 0%;
    background: linear-gradient(90deg, #10b981, #38bdf8);
    transition: width 0.2s ease;
  }

  .apk-btn-overlay-launch {
    height: 32px;
    padding: 0 clamp(6px, 2.2vw, 10px) !important;
    border-radius: 8px !important;
    font-size: clamp(11px, 3.1vw, 12px) !important;
    font-weight: 800 !important;
    background: linear-gradient(135deg, #2563eb, #1d4ed8) !important;
    border: 1px solid #60a5fa !important;
    color: #ffffff !important;
    white-space: nowrap;
    display: inline-flex;
    align-items: center;
    gap: 3px;
    flex-shrink: 0;
  }

  .apk-tool-btn {
    flex: 1 1 0;
    min-width: 0;
    height: 31px;
    padding: 0 4px !important;
    border-radius: 8px !important;
    font-size: clamp(10.2px, 2.9vw, 11.5px) !important;
    font-weight: 700 !important;
    background: #172136 !important;
    border: 1px solid #334155 !important;
    color: #e2e8f0 !important;
    display: inline-flex;
    align-items: center;
    justify-content: center;
    gap: 2px;
    white-space: nowrap;
    overflow: hidden;
    text-overflow: ellipsis;
  }

  .apk-tool-btn.icon-only {
    flex: 0 0 28px;
    font-size: 13.5px !important;
    padding: 0 !important;
  }

  .apk-tool-btn:disabled {
    opacity: 0.42;
  }

  .apk-tool-btn.diff-btn {
    background: #2b1c10 !important;
    border-color: #d97706 !important;
    color: #fde68a !important;
  }

  .apk-tool-btn.diff-btn.has-diff {
    background: linear-gradient(135deg, #9a3412, #b45309) !important;
    border-color: #fde047 !important;
    color: #ffffff !important;
    box-shadow: 0 0 10px rgba(251, 191, 36, 0.45);
    animation: apkPulseBadge 1.4s infinite alternate;
  }

  @keyframes apkPulseBadge {
    from { transform: scale(1); }
    to { transform: scale(1.03); }
  }

  .apk-badge {
    display: inline-flex;
    align-items: center;
    justify-content: center;
    min-width: 15px;
    height: 15px;
    padding: 0 3px;
    border-radius: 999px;
    font-size: 9.5px;
    font-weight: 900;
    background: #334155;
    color: #f8fafc;
    flex-shrink: 0;
  }

  .apk-badge.warn {
    background: #facc15;
    color: #0f172a;
  }

  /* --- 1-SCREEN PHONE & EMULATOR FULL-FIT WORKSPACE --- */
  main.workspace {
    display: flex !important;
    flex-direction: column !important;
    align-items: stretch !important;
    flex: 1 1 auto !important;
    gap: 7px !important;
    padding: 6px 6px 14px !important;
    width: 100% !important;
    max-width: 680px !important;
    min-width: 0 !important;
    margin: 0 auto !important;
  }

  .panel.col-board {
    padding: 7px 7px !important;
    border-radius: 12px !important;
    width: 100% !important;
    max-width: 100% !important;
    min-width: 0 !important;
    display: flex !important;
    flex-direction: column !important;
    flex: 1 1 auto !important;
  }

  .board-svg-wrap {
    width: 100% !important;
    max-width: 100% !important;
    border-radius: 10px !important;
    flex: 1 1 auto !important;
    min-height: 190px;
  }

  #boardSvg {
    width: 100% !important;
    max-height: clamp(205px, 41dvh, 430px) !important;
  }

  .board-statusbar {
    margin-top: 5px !important;
    font-size: clamp(10px, 2.8vw, 11.2px) !important;
    gap: 4px 6px !important;
    justify-content: space-between !important;
    align-items: center !important;
    flex-wrap: wrap !important;
    width: 100% !important;
  }

  .board-statusbar > span:nth-child(3) {
    display: none !important;
  }

  .apk-rot-pill-btn {
    padding: 2px 7px !important;
    font-size: clamp(10px, 2.8vw, 11px) !important;
    font-weight: 800 !important;
    border-radius: 6px !important;
    background: #172554 !important;
    border: 1px solid #60a5fa !important;
    color: #dbeafe !important;
    white-space: nowrap;
  }

  .apk-board-tap-hint {
    color: #93c5fd;
    font-weight: 600;
    font-size: clamp(10px, 2.7vw, 11px);
  }

  /* Compact 3-Slot Hand Bar — zero horizontal overflow on narrow 320px screens! */
  .hand-section {
    margin-top: 6px !important;
    padding-top: 6px !important;
    width: 100% !important;
    min-width: 0 !important;
  }

  .hand-section > div:first-child {
    margin-bottom: 4px !important;
    flex-wrap: wrap !important;
    gap: 4px !important;
  }

  .hand-section .panel-title {
    font-size: clamp(11px, 3.1vw, 12.2px) !important;
    min-width: 0;
  }

  #btnClearHand {
    padding: 2px 7px !important;
    font-size: clamp(10.2px, 2.8vw, 11px) !important;
    white-space: nowrap;
  }

  .hand-grid {
    display: grid !important;
    grid-template-columns: repeat(3, minmax(0, 1fr)) !important;
    gap: 5px !important;
    margin-top: 4px !important;
    width: 100% !important;
    min-width: 0 !important;
  }

  .hand-slot {
    min-width: 0 !important;
    min-height: 74px !important;
    padding: 4px 4px !important;
    gap: 3px !important;
    border-radius: 9px !important;
    overflow: hidden !important;
  }

  .hand-slot-header {
    gap: 2px !important;
    min-width: 0 !important;
  }

  .hand-top-hex-preview {
    width: 26px !important;
    height: 23px !important;
  }

  .mini-layer-pill {
    width: 100% !important;
    min-width: 0 !important;
    padding: 2px 4px !important;
    font-size: clamp(9.6px, 2.7vw, 11px) !important;
    border-radius: 5px !important;
    gap: 2px !important;
    overflow: hidden !important;
  }

  .mini-layer-pill .cb-icon-chip {
    padding: 1px 3px !important;
    font-size: clamp(9.5px, 2.6vw, 11px) !important;
    min-width: 0;
    overflow: hidden;
    text-overflow: ellipsis;
  }

  .mini-layer-pill .cb-icon-thumb {
    width: 15px !important;
    height: 13px !important;
  }

  /* Compact Best Plan Card right under Hand Slots */
  .panel.col-solver {
    padding: 2px 0 0 !important;
    border-radius: 12px !important;
    background: transparent !important;
    border: none !important;
    box-shadow: none !important;
    width: 100% !important;
    max-width: 100% !important;
    min-width: 0 !important;
  }

  .col-solver > .solve-controls-bar {
    display: none !important;
  }

  #plansContainer .plan-card {
    padding: 8px 9px !important;
    border-radius: 11px !important;
    margin: 0 !important;
  }

  #plansContainer .plan-head {
    margin-bottom: 4px !important;
  }

  #plansContainer .plan-summary {
    font-size: 11.3px !important;
    margin-bottom: 5px !important;
  }

  #plansContainer .step-item {
    padding: 5px 7px !important;
    margin-bottom: 4px !important;
    font-size: 12px !important;
  }

  #plansContainer .plan-actions {
    margin-top: 6px !important;
    gap: 5px !important;
  }

  #plansContainer .plan-actions button {
    padding: 7px 8px !important;
    font-size: 12px !important;
    font-weight: 800 !important;
  }

  /* --- SLIDE-UP BOTTOM SHEET STACK COMPOSER (.col-editor) --- */
  .apk-sheet-backdrop {
    position: fixed;
    inset: 0;
    background: rgba(2, 6, 23, 0.45);
    z-index: 900;
    opacity: 0;
    pointer-events: none;
    transition: opacity 0.18s ease;
  }

  .apk-sheet-backdrop.open {
    opacity: 1;
    pointer-events: auto;
  }

  .panel.col-editor {
    position: fixed !important;
    left: 0 !important;
    right: 0 !important;
    bottom: 0 !important;
    max-width: 560px !important;
    margin: 0 auto !important;
    max-height: 62vh !important;
    overflow-y: auto !important;
    z-index: 950 !important;
    background: var(--apk-sheet-bg) !important;
    border: 1.5px solid #475569 !important;
    border-bottom: none !important;
    border-radius: 18px 18px 0 0 !important;
    padding: 8px 10px 16px !important;
    box-shadow: 0 -10px 30px rgba(0, 0, 0, 0.72) !important;
    transform: translateY(108%);
    transition: transform 0.2s cubic-bezier(0.2, 0.8, 0.2, 1);
  }

  .panel.col-editor.sheet-open {
    transform: translateY(0%);
  }

  .apk-editor-sticky-top {
    position: sticky;
    top: -8px;
    z-index: 12;
    background: var(--apk-sheet-bg);
    margin: -8px -10px 4px;
    padding: 0 10px 4px;
    border-bottom: 1px solid rgba(51, 65, 85, 0.55);
  }

  .apk-sheet-handle-zone {
    width: 100%;
    padding: 9px 0 8px;
    display: flex;
    align-items: center;
    justify-content: center;
    touch-action: none;
    user-select: none;
    -webkit-user-select: none;
    cursor: grab;
    flex-shrink: 0;
  }

  .apk-sheet-handle {
    width: 44px;
    height: 5px;
    border-radius: 99px;
    background: #64748b;
    transition: background 0.15s ease, transform 0.15s ease;
  }

  .apk-sheet-handle-zone:active .apk-sheet-handle,
  .is-dragging .apk-sheet-handle {
    background: #fbbf24;
    transform: scaleX(1.12);
  }

  .apk-editor-top-row {
    display: flex;
    align-items: center;
    justify-content: space-between;
    gap: 6px;
    margin-bottom: 6px;
  }

  .apk-target-tabs {
    display: flex;
    gap: 4px;
    flex-wrap: nowrap;
    overflow-x: auto;
    padding-bottom: 2px;
    margin-bottom: 6px;
  }

  .apk-target-tab {
    padding: 4px 8px !important;
    border-radius: 7px !important;
    font-size: 11.2px !important;
    font-weight: 800 !important;
    background: #1e293b !important;
    border: 1px solid #334155 !important;
    color: #cbd5e1 !important;
    white-space: nowrap;
  }

  .apk-target-tab.active {
    background: #3b2506 !important;
    border-color: #fbbf24 !important;
    color: #fde68a !important;
  }

  .panel.col-editor .selected-target-box {
    padding: 5px 8px !important;
    margin-bottom: 6px !important;
  }

  .panel.col-editor .brush-toggle-box {
    padding: 5px 8px !important;
    margin-bottom: 6px !important;
    font-size: 11.5px !important;
  }

  .panel.col-editor .count-selector-row {
    margin-bottom: 6px !important;
    gap: 4px !important;
  }

  .panel.col-editor .cnt-btn {
    padding: 7px 0 !important;
    font-size: 13.5px !important;
    font-weight: 800 !important;
    border-radius: 7px !important;
  }

  .panel.col-editor .palette-grid {
    display: grid !important;
    grid-template-columns: repeat(4, 1fr) !important;
    gap: 5px !important;
    margin: 5px 0 7px !important;
  }

  .panel.col-editor .color-swatch-btn {
    padding: 5px 3px !important;
    font-size: 11px !important;
    border-radius: 8px !important;
    min-height: 54px !important;
  }

  .panel.col-editor .swatch-game-hex {
    width: 34px !important;
    height: 30px !important;
  }

  /* --- GENERIC SLIDE-UP MODAL SHEETS (LOG, SAVES/MENU, BOARD DIFF SYNC) --- */
  .apk-modal-sheet {
    position: fixed;
    left: 0;
    right: 0;
    bottom: 0;
    max-width: 560px;
    margin: 0 auto;
    max-height: 84vh;
    display: flex;
    flex-direction: column;
    z-index: 960;
    background: #0f172a;
    border: 1.5px solid #475569;
    border-bottom: none;
    border-radius: 18px 18px 0 0;
    box-shadow: 0 -12px 34px rgba(0, 0, 0, 0.8);
    transform: translateY(108%);
    transition: transform 0.22s cubic-bezier(0.2, 0.8, 0.2, 1);
  }

  .apk-modal-sheet.open {
    transform: translateY(0%);
  }

  .apk-modal-hdr {
    display: flex;
    align-items: center;
    justify-content: space-between;
    gap: 8px;
    padding: 10px 12px 8px;
    border-bottom: 1px solid #334155;
    flex-shrink: 0;
  }

  .apk-modal-title {
    font-size: 14px;
    font-weight: 800;
    color: #f8fafc;
  }

  .apk-modal-body {
    padding: 10px 12px 16px;
    overflow-y: auto;
    flex: 1;
  }

  .apk-modal-ftr {
    padding: 8px 12px 12px;
    border-top: 1px solid #334155;
    background: #0b1120;
    display: flex;
    gap: 7px;
    flex-shrink: 0;
  }

  /* Ensure Save drawer & Log section look clean inside modal sheets */
  #apkMenuModal #saveLoadDrawer {
    display: block !important;
    margin: 0 !important;
    padding: 0 !important;
  }

  #apkMenuModal .save-drawer-inner {
    border: none !important;
    padding: 0 !important;
    background: transparent !important;
  }

  #apkMenuModal #btnCloseSavePanel {
    display: none !important;
  }

  #apkLogModal .action-log-list {
    max-height: 54vh !important;
  }

  #apkLogModal #btnDownloadLog,
  #apkLogModal #btnCopyLog {
    display: none !important;
  }

  #apkLogModal #btnDownloadLog,
  #apkLogModal #btnCopyLog {
    display: none !important;
  }

  .apk-log-quickbar {
    display: grid;
    grid-template-columns: 1.35fr 1fr;
    gap: 7px;
    margin-bottom: 9px;
  }

  .apk-log-dl-big {
    padding: 9px 10px !important;
    border-radius: 9px !important;
    font-size: 12.8px !important;
    font-weight: 900 !important;
    background: linear-gradient(135deg, #059669, #047857) !important;
    border: 1px solid #34d399 !important;
    color: #ffffff !important;
    display: inline-flex;
    align-items: center;
    justify-content: center;
    gap: 5px;
  }

  .apk-log-copy-big {
    padding: 9px 10px !important;
    border-radius: 9px !important;
    font-size: 12.5px !important;
    font-weight: 800 !important;
    background: #1e293b !important;
    border: 1px solid #475569 !important;
    color: #e2e8f0 !important;
    display: inline-flex;
    align-items: center;
    justify-content: center;
    gap: 5px;
  }

  /* --- FAQ GUIDE CARDS --- */
  .apk-btn-faq {
    height: 32px;
    padding: 0 clamp(6px, 2vw, 9px) !important;
    border-radius: 8px !important;
    font-size: clamp(11px, 3vw, 12px) !important;
    font-weight: 800 !important;
    background: #1e1b4b !important;
    border: 1px solid #818cf8 !important;
    color: #e0e7ff !important;
    white-space: nowrap;
    display: inline-flex;
    align-items: center;
    gap: 2px;
    flex-shrink: 0;
  }

  .panel.col-editor,
  .apk-modal-sheet {
    max-width: 680px !important;
  }

  .panel.col-editor .layer-row {
    gap: 5px !important;
    padding: 5px 6px !important;
    flex-wrap: nowrap !important;
    min-width: 0 !important;
  }

  .panel.col-editor .layer-row > div:first-child {
    min-width: 0 !important;
    overflow: hidden !important;
  }

  .panel.col-editor .layer-row-actions {
    flex-shrink: 0 !important;
    gap: 3px !important;
  }

  .panel.col-editor .layer-row-actions button {
    padding: 3px 8px !important;
    min-width: 28px !important;
  }

  .apk-faq-card {
    background: #172136;
    border: 1px solid #334155;
    border-radius: 11px;
    padding: 10px 11px;
    margin-bottom: 9px;
  }

  .apk-faq-card-title {
    font-size: 13px;
    font-weight: 800;
    color: #fde68a;
    margin-bottom: 5px;
    display: flex;
    align-items: center;
    gap: 6px;
  }

  .apk-faq-card-body {
    font-size: 12px;
    line-height: 1.46;
    color: #e2e8f0;
  }

  .apk-faq-card-body b {
    color: #ffffff;
  }

  .apk-faq-kbd {
    display: inline-block;
    padding: 1px 6px;
    border-radius: 5px;
    background: #0f172a;
    border: 1px solid #475569;
    color: #93c5fd;
    font-size: 11px;
    font-weight: 800;
    white-space: nowrap;
  }

  /* --- BOARD DIFF APPROVAL CARDS --- */
  .apk-diff-card {
    background: #172136;
    border: 1.5px solid #334155;
    border-radius: 10px;
    padding: 8px 9px;
    margin-bottom: 7px;
  }

  .apk-diff-card.selected {
    border-color: #f59e0b;
    background: rgba(120, 53, 15, 0.22);
  }

  .apk-diff-card-top {
    display: flex;
    align-items: center;
    justify-content: space-between;
    gap: 6px;
    margin-bottom: 5px;
  }

  .apk-diff-chk-lbl {
    display: inline-flex;
    align-items: center;
    gap: 7px;
    font-size: 13px;
    font-weight: 800;
    color: #fef3c7;
    cursor: pointer;
  }

  .apk-diff-chk-lbl input[type="checkbox"] {
    width: 18px;
    height: 18px;
    accent-color: #f59e0b;
  }

  .apk-diff-compare {
    display: grid;
    grid-template-columns: 1fr auto 1fr;
    align-items: center;
    gap: 6px;
    font-size: 11.8px;
    background: rgba(15, 23, 42, 0.72);
    border-radius: 8px;
    padding: 6px 7px;
  }

  .apk-diff-side-lbl {
    font-size: 10px;
    color: #94a3b8;
    margin-bottom: 2px;
    font-weight: 700;
  }

  .apk-diff-note {
    margin-top: 5px;
    font-size: 11px;
    color: #cbd5e1;
    background: rgba(51, 65, 85, 0.45);
    padding: 3px 6px;
    border-radius: 5px;
  }

  .apk-diff-inline-edit {
    margin-top: 6px;
    padding-top: 6px;
    border-top: 1px dashed rgba(148, 163, 184, 0.3);
    display: flex;
    flex-direction: column;
    gap: 5px;
  }

  .apk-diff-edit-layer {
    display: flex;
    align-items: center;
    justify-content: space-between;
    gap: 5px;
    background: #0f172a;
    padding: 4px 6px;
    border-radius: 6px;
    font-size: 11.5px;
  }

  /* v1.0.15+ Anti-Misclick Slot Position Badges & Red Reroll Booster Alert */
  .apk-slot-pill {
    display: inline-flex;
    align-items: center;
    padding: 2px 6px;
    border-radius: 6px;
    font-size: 11px;
    font-weight: 800;
    letter-spacing: 0.01em;
    border: 1.5px solid transparent;
    white-space: nowrap;
  }
  .apk-slot-pill.s1 {
    background: #3b2506;
    color: #fde68a;
    border-color: #f59e0b;
  }
  .apk-slot-pill.s2 {
    background: #0c2e47;
    color: #bae6fd;
    border-color: #38bdf8;
  }
  .apk-slot-pill.s3 {
    background: #2e1047;
    color: #f3e8ff;
    border-color: #c084fc;
  }
  .apk-reroll-alert {
    margin: 4px 0 6px 0;
    padding: 7px 9px;
    border-radius: 8px;
    font-size: 12px;
    font-weight: 800;
    line-height: 1.32;
    color: #fff1f2;
    background: linear-gradient(135deg, #7f1d1d, #991b1b);
    border: 2px solid #f87171;
    box-shadow: 0 0 10px rgba(248, 113, 113, 0.3);
  }
  .apk-reroll-alert.warn {
    background: linear-gradient(135deg, #78350f, #92400e);
    border-color: #fbbf24;
    color: #fef3c7;
    box-shadow: 0 0 8px rgba(251, 191, 36, 0.24);
  }

  /* v1.0.23 "SUD" Suicide Squad Clan Title Bar + Crest Showcase + Smart Mode Advisor */
  .apk-sud-brandbar {
    display: flex;
    align-items: center;
    justify-content: space-between;
    gap: 6px;
    padding: 3px 8px;
    border-radius: 9px;
    background: linear-gradient(90deg, rgba(28, 12, 18, 0.96) 0%, rgba(17, 24, 39, 0.96) 52%, rgba(28, 12, 18, 0.96) 100%);
    border: 1px solid rgba(217, 119, 6, 0.68);
    box-shadow: 0 2px 10px rgba(185, 28, 28, 0.22);
    cursor: pointer;
    user-select: none;
  }

  .apk-sud-brand-left {
    display: inline-flex;
    align-items: center;
    gap: 12px;
    min-width: 0;
  }

  .apk-sud-mini-crest {
    width: 24px;
    height: 23px;
    border-radius: 5px;
    object-fit: cover;
    border: 1px solid rgba(251, 191, 36, 0.7);
    flex-shrink: 0;
  }

  .apk-sud-title-text {
    display: inline-flex;
    align-items: baseline;
    gap: 6px;
    white-space: nowrap;
    overflow: hidden;
    text-overflow: ellipsis;
  }

  .apk-sud-tag {
    font-size: clamp(12.5px, 3.6vw, 14.5px);
    font-weight: 900;
    letter-spacing: 0.04em;
    color: #ef4444;
    text-shadow: 0 0 8px rgba(239, 68, 68, 0.55), 0 1px 2px #000;
  }

  .apk-sud-sub {
    font-size: clamp(12px, 3.4vw, 13.8px);
    font-weight: 800;
    letter-spacing: 0.02em;
    background: linear-gradient(180deg, #fef3c7 0%, #d97706 100%);
    -webkit-background-clip: text;
    -webkit-text-fill-color: transparent;
    filter: drop-shadow(0 1px 2px rgba(0, 0, 0, 0.85));
  }

  .apk-sud-end-icon {
    width: 22px;
    height: 22px;
    flex-shrink: 0;
    filter: drop-shadow(0 1px 3px rgba(239, 68, 68, 0.5));
  }

  .apk-sud-crest-pill {
    font-size: 10px;
    font-weight: 800;
    padding: 2px 6px;
    border-radius: 6px;
    background: rgba(120, 53, 15, 0.45);
    border: 1px solid rgba(251, 191, 36, 0.55);
    color: #fde68a;
    white-space: nowrap;
    flex-shrink: 0;
  }

  .apk-crest-showcase-card {
    display: flex;
    flex-direction: column;
    align-items: center;
    justify-content: center;
    padding: 10px 10px 11px;
    border-radius: 12px;
    background: radial-gradient(circle at 50% 42%, #151c2e 0%, #0d1220 78%);
    border: 1px solid rgba(217, 119, 6, 0.5);
    box-shadow: 0 6px 20px rgba(0, 0, 0, 0.45);
    text-align: center;
  }

  .apk-crest-showcase-img {
    width: min(74vw, 248px);
    max-height: 28dvh;
    height: auto;
    object-fit: contain;
    border-radius: 10px;
    cursor: pointer;
    filter: drop-shadow(0 4px 14px rgba(0, 0, 0, 0.65));
  }

  .apk-crest-showcase-hint {
    margin-top: 6px;
    font-size: clamp(10.5px, 2.9vw, 11.8px);
    color: #cbd5e1;
    line-height: 1.35;
  }

  .apk-mode-advice-bar {
    display: flex;
    align-items: center;
    justify-content: space-between;
    gap: 6px;
    padding: 7px 9px;
    margin-bottom: 6px;
    border-radius: 10px;
    background: linear-gradient(135deg, #0f2a44, #172554);
    border: 1.5px solid #38bdf8;
    color: #e0f2fe;
    font-size: clamp(10.8px, 3vw, 11.8px);
    font-weight: 700;
    line-height: 1.32;
  }

  .apk-mode-advice-bar.urgent {
    background: linear-gradient(135deg, #5c2409, #7c2d12);
    border-color: #fbbf24;
    color: #fef3c7;
    box-shadow: 0 0 10px rgba(245, 158, 11, 0.28);
  }
</style>
<script id="apk-state-defaults">
  window.APK_SLOT_LABELS = {
    1: "⬅ ЛЕВ #1",
    2: "⬆ СР #2",
    3: "➡ ПРАВ #3"
  };
  window.APK_SUD_CREST_URI = "__SUD_CREST_DATA_URI__";
  window.apkPendingDiff = null;
  window.apkJustChangedStage = true;
  window.apkDismissedModeAdviceKey = "";
  window.apkLatestModeAdvice = null;
  try {
    localStorage.setItem("dj_solver_icon_style_v1", JSON.stringify("tuan"));
    var rawAuto = localStorage.getItem("dj_solver_autosave_v1");
    if (rawAuto) {
      var parsedAuto = JSON.parse(rawAuto);
      if (parsedAuto && typeof parsedAuto === "object") {
        parsedAuto.showStackInfo = false;
        localStorage.setItem("dj_solver_autosave_v1", JSON.stringify(parsedAuto));
      }
    }
  } catch (e) {}
</script>
"""

SOLVE_HAND_BRIDGE_OLD = """  function solveHand(req, config) {
    const t0 = performance.now();
    const adjTable = config.adjacentIds;
    const paletteNames = {};
    for (const p of config.palette) paletteNames[p.id] = p.nameRu;

    const baseCells = buildBoardDict(req.cells);
    applyInitialUnlocks(baseCells, req.curScore);
    const colorProbs = normalizeColorProbsJs(req.colorWeights);
    const initialForecast = computeBoardReceptivityJs(baseCells, adjTable, colorProbs);"""

SOLVE_HAND_BRIDGE_NEW = """  function solveHand(req, config) {
    const t0 = performance.now();
    const adjTable = config.adjacentIds;
    const paletteNames = {};
    for (const p of config.palette) paletteNames[p.id] = p.nameRu;

    const baseCells = buildBoardDict(req.cells);
    applyInitialUnlocks(baseCells, req.curScore);
    const colorProbs = normalizeColorProbsJs(req.colorWeights);
    const initialForecast = computeBoardReceptivityJs(baseCells, adjTable, colorProbs);

    if (typeof window !== "undefined" && window.AndroidHost && typeof window.AndroidHost.solveHandJson === "function") {
      try {
        const cellsObj = {};
        for (const c of req.cells || []) {
          cellsObj[String(c.cellId)] = {
            cellId: c.cellId,
            exist: 1,
            lockScore: c.lockScore || 0,
            lockCardScore: c.lockCardScore || 0,
            layers: c.layers || [],
          };
        }
        const waitsObj = {};
        for (const w of req.waits || []) {
          waitsObj[String(w.slotId)] = w.layers || [];
        }
        const rawJson = window.AndroidHost.solveHandJson(JSON.stringify({
          stageNo: req.stageNo || 7,
          curScore: req.curScore || 0,
          mode: req.mode || "balanced",
          topK: req.topK || 1,
          isFreshStageScan: !!window.apkJustChangedStage,
          cells: cellsObj,
          waits: waitsObj,
        }));
        const jRes = JSON.parse(rawJson || "{}");
        if (jRes.suggestedMode) {
          window.apkLatestModeAdvice = {
            suggestedMode: jRes.suggestedMode || "",
            suggestedModeTitleRu: jRes.suggestedModeTitleRu || "",
            suggestedModeReasonRu: jRes.suggestedModeReasonRu || "",
            urgent: !!jRes.suggestedModeUrgent,
          };
        } else {
          window.apkLatestModeAdvice = null;
        }
        if (Array.isArray(jRes.plans) && jRes.plans.length > 0) {
          const plansOut = jRes.plans.map((jp) => {
            let prevScore = req.curScore || 0;
            const stepsDetail = (jp.steps || []).map((js, sIdx) => {
              const boardAfterArr = [];
              const baMap = js.boardAfter || {};
              for (let cid = 1; cid <= MAX_POS_COUNT; cid++) {
                const bc = baMap[String(cid)];
                if (bc && bc.exist === 1) {
                  boardAfterArr.push({
                    cellId: cid,
                    exist: 1,
                    lockScore: bc.lockScore || 0,
                    lockCardScore: bc.lockCardScore || 0,
                    layers: (bc.layers || []).map((l) => ({ colour: l.colour, count: l.count })),
                  });
                } else {
                  boardAfterArr.push(cloneCell(baseCells[cid]));
                }
              }
              const cl = (js.cardLayers || []).map((l) => ({ colour: l.colour, count: l.count }));
              const topCol = cl.length ? cl[cl.length - 1].colour : 1;
              const scAfter = Number(js.scoreAfter ?? prevScore);
              const detail = {
                stepNum: js.step || (sIdx + 1),
                slotId: js.slotId,
                targetCellId: js.targetCellId,
                cardLayers: cl,
                topColorName: paletteNames[topCol] || ("Цвет " + topCol),
                scoreBefore: prevScore,
                scoreAfter: scAfter,
                gainedScore: Number(js.gainedScore || 0),
                baseBlockScore: Number(js.gainedScore || 0),
                comboBonusScore: 0,
                eliminations: js.gainedScore > 0 ? 1 : 0,
                blocksEliminated: 0,
                unlockedCells: js.unlockedCells || [],
                emptyCellsAfter: Number(js.emptyCellsAfter ?? 0),
                mergeStr: "",
                events: [],
                boardAfter: boardAfterArr,
              };
              prevScore = scAfter;
              return detail;
            });
            const reasons = [];
            if (jp.isWin) reasons.push("Победа этапа за " + stepsDetail.length + " ход(а)");
            if (jp.totalGainedScore > 0) reasons.push("+" + jp.totalGainedScore + " очк.");
            if (jp.totalComboBonus > 0) reasons.push("комбо +" + jp.totalComboBonus);
            reasons.push("пусто: " + jp.finalEmptyCells);
            if (!jp.isWin && jp.finalEmptyCells > 0) {
              reasons.push("след. рука: " + Math.round(jp.mergeProbNextHand || 0) + "%");
            }
            if (Array.isArray(jp.walledWarnings) && jp.walledWarnings.length > 0) {
              reasons.push("⚠ зажато: " + jp.walledWarnings.join(", "));
            }
            return {
              rank: jp.rank || 1,
              evalScore: jp.evalScore || 0,
              isWin: !!jp.isWin,
              isDead: !jp.isWin && (jp.finalEmptyCells === 0 || !!jp.deadlockStopAll),
              recommendReroll: !!jp.recommendReroll,
              partialBeforeReroll: !!jp.partialBeforeReroll,
              deadlockStopAll: !!jp.deadlockStopAll,
              rerollReason: jp.rerollReason || "",
              hammerCellId: jp.hammerCellId || 0,
              hammerReasonRu: jp.hammerReasonRu || "",
              walledWarnings: jp.walledWarnings || [],
              initialScore: req.curScore || 0,
              finalScore: jp.finalScore,
              totalGainedScore: jp.totalGainedScore,
              totalComboBonus: jp.totalComboBonus || 0,
              totalEliminations: 0,
              totalBlocksCleared: 0,
              totalUnlocked: 0,
              finalEmptyCells: jp.finalEmptyCells,
              nextHandForecast: {
                mergeProbNextHand: jp.mergeProbNextHand || 0,
                burstProbNextHand: jp.burstProbNextHand || 0,
                openColors: jp.openColors || [],
                burstReadyColors: jp.burstReadyColors || [],
              },
              summary: reasons.join(" • "),
              steps: stepsDetail,
            };
          });
          return {
            ok: true,
            elapsedMs: Math.round((performance.now() - t0) * 10) / 10,
            evaluatedStates: 1680,
            boardForecast: initialForecast,
            plans: plansOut,
            message: "",
          };
        }
      } catch (bridgeErr) {
        console.warn("AndroidHost.solveHandJson fallback:", bridgeErr);
      }
    }"""

APK_PHONE_RUNTIME_JS = r"""
    // =========================================================================
    // v1.0.16 PHONE SINGLE-SCREEN + SLIDE-UP SHEETS + BOARD DIFF APPROVAL UX
    // =========================================================================
    const apkUi = {
      activeSheet: null, // 'editor' | 'log' | 'menu' | 'diff' | 'faq' | 'crest' | null
      editingDiffIdx: -1,
      updateScoreAndHandOnDiff: true,
      useHintsInCalc: false,
      saveUiScreenshots: false,
    };

    // Enforce user-requested stack editor defaults: count = 1, brush action = "append" (add layer on top)
    state.brushCount = 1;
    state.brushAction = "append";
    if (el.brushActionSelect) {
      el.brushActionSelect.value = "append";
    }
    renderStackComposer();

    const backdrop = document.createElement("div");
    backdrop.className = "apk-sheet-backdrop";
    document.body.appendChild(backdrop);

    // Custom Plague Doctor × Suicide Squad inline vector emblem (crossed red syringe + spiked bat + beak mask + X eye + crown)
    const PLAGUE_SQUAD_END_SVG = `
      <svg class="apk-sud-end-icon" viewBox="0 0 36 36" xmlns="http://www.w3.org/2000/svg" aria-hidden="true">
        <line x1="5" y1="31" x2="31" y2="5" stroke="#cbd5e1" stroke-width="2.6" stroke-linecap="round"/>
        <line x1="25" y1="11" x2="30" y2="6" stroke="#ef4444" stroke-width="4.2" stroke-linecap="round"/>
        <line x1="5" y1="5" x2="31" y2="31" stroke="#b45309" stroke-width="3.2" stroke-linecap="round"/>
        <line x1="7" y1="8" x2="11" y2="6" stroke="#facc15" stroke-width="1.5"/>
        <line x1="9" y1="11" x2="13" y2="9" stroke="#facc15" stroke-width="1.5"/>
        <path d="M13 8 L15.5 4 L18 7.5 L20.5 4 L23 8 Z" fill="#fbbf24" stroke="#78350f" stroke-width="0.9"/>
        <path d="M9 12 Q18 8 27 12 L24 14 Q18 12 12 14 Z" fill="#1e293b" stroke="#f8fafc" stroke-width="1"/>
        <path d="M12 13 C12 9, 24 9, 24 13 L25 20 L31 25 C25 27, 19 26, 15 23 L12 19 Z" fill="#0f172a" stroke="#fbbf24" stroke-width="1.3"/>
        <circle cx="16.5" cy="16.5" r="2.7" fill="#ef4444"/>
        <path d="M15.3 15.3 L17.7 17.7 M17.7 15.3 L15.3 17.7" stroke="#ffffff" stroke-width="1.1" stroke-linecap="round"/>
        <circle cx="21.5" cy="16.3" r="1.9" fill="#38bdf8"/>
      </svg>
    `;

    // 1. Mount compact sticky phone header with "SUD" Suicide Squad title bar inside .topbar
    const topbarEl = document.querySelector("header.topbar");
    const hdrWrap = document.createElement("div");
    hdrWrap.className = "apk-hdr-wrap";
    hdrWrap.innerHTML = `
      <div class="apk-sud-brandbar" id="apkSudBrandBar" title="Открыть герб [SUD] Suicide Squad — Двойная радость!">
        <div class="apk-sud-brand-left">
          <img src="${window.APK_SUD_CREST_URI}" class="apk-sud-mini-crest" alt="SUD" />
          <div class="apk-sud-title-text">
            <span class="apk-sud-tag">"SUD"</span>
            <span class="apk-sud-sub">Suicide Squad</span>
          </div>
        </div>
      </div>
      <div class="apk-hdr-row1">
        <div class="apk-pill-box" id="apkStageBox">
          <span class="apk-pill-lbl">Эт.</span>
        </div>
        <div class="apk-pill-box apk-score-box" id="apkScoreBox">
          <span class="apk-pill-lbl">Счёт</span>
          <span class="apk-goal-text" id="apkGoalShort">/ 80</span>
          <div class="apk-mini-prog"><div class="apk-mini-prog-fill" id="apkMiniProgFill"></div></div>
        </div>
        <button type="button" id="btnApkFaq" class="apk-btn-faq" title="Гайд и инструкция по приложению">❓ FAQ</button>
        <button type="button" id="btnApkOverlay" class="apk-btn-overlay-launch">🚀 Оверлей</button>
      </div>
      <div class="apk-hdr-row2">
        <button type="button" id="btnApkDiff" class="apk-tool-btn diff-btn" title="Сверить столбцы поля с игрой (с подтверждением)">
          🔍 Сверка <span id="apkDiffBadge" class="apk-badge hidden">0</span>
        </button>
        <button type="button" id="btnApkEditor" class="apk-tool-btn" title="Открыть редактор выбранной клетки или слота руки">
          ✏️ Стопка
        </button>
        <button type="button" id="btnApkUndoProxy" class="apk-tool-btn icon-only" title="Отменить действие">↶</button>
        <button type="button" id="btnApkRedoProxy" class="apk-tool-btn icon-only" title="Повторить действие">↷</button>
        <button type="button" id="btnApkLog" class="apk-tool-btn" title="Журнал ходов, откат и выгрузка .log">
          📜 Лог <span id="apkLogBadge" class="apk-badge">0</span>
        </button>
        <button type="button" id="btnApkMenu" class="apk-tool-btn" title="Сохранения, .json, сброс этапа и настройки">
          💾 Меню
        </button>
      </div>
    `;
    topbarEl.insertBefore(hdrWrap, topbarEl.firstChild);
    document.getElementById("apkStageBox").appendChild(el.stageSelect);
    const apkScoreBox = document.getElementById("apkScoreBox");
    apkScoreBox.insertBefore(el.curScoreInput, document.getElementById("apkGoalShort"));
    document.body.classList.add("apk-phone-ready");

    // Shorten stage options text for compact phone select ("Этап 7 (цель 450...)" -> "7 (450)")
    function compactStageOptions() {
      Array.from(el.stageSelect.options).forEach((opt) => {
        const stg = getStageCfg(parseInt(opt.value, 10));
        if (stg) {
          opt.textContent = `${stg.stageNo} (${stg.targetScore})`;
        }
      });
    }
    compactStageOptions();

    // Add rotation pill + tap hint to board status bar
    const statusBarEl = document.querySelector(".board-statusbar");
    if (statusBarEl) {
      const rotBtn = document.createElement("button");
      rotBtn.type = "button";
      rotBtn.id = "btnApkRotStatus";
      rotBtn.className = "apk-rot-pill-btn";
      rotBtn.title = "Текущий ракурс поля (автоопределение 0°..300° со скриншота / тап = повернуть на +60°)";
      rotBtn.textContent = `🔄 ${state.rotationDeg || 0}°`;
      rotBtn.addEventListener("click", () => {
        state.rotationDeg = (((state.rotationDeg || 0) + 60) % 360 + 360) % 360;
        if (el.rotLabel) el.rotLabel.textContent = `${state.rotationDeg}°`;
        rotBtn.textContent = `🔄 ${state.rotationDeg}°`;
        renderBoardSvg();
        saveAutoProgress();
      });
      statusBarEl.appendChild(rotBtn);

      const hintSpan = document.createElement("span");
      hintSpan.className = "apk-board-tap-hint";
      hintSpan.textContent = "👆 Тап = слои";
      statusBarEl.appendChild(hintSpan);
    }

    // 2. Decorate .col-editor as a Slide-Up Bottom Sheet with quick target switcher & sticky swipe-down top bar
    const colEditorEl = document.querySelector(".panel.col-editor");
    const editorTopBar = document.createElement("div");
    editorTopBar.className = "apk-editor-sticky-top";
    editorTopBar.innerHTML = `
      <div class="apk-sheet-handle-zone" data-sheet-handle="1" title="Потяните вниз (или коснитесь полоски), чтобы скрыть">
        <div class="apk-sheet-handle"></div>
      </div>
      <div class="apk-editor-top-row">
        <div style="font-size:13px; font-weight:800; color:#f8fafc;">🎨 Редактор стопки</div>
        <div style="display:flex; gap:6px;">
          <button type="button" id="btnApkCloseEditor" class="btn-success" style="padding:4px 12px; font-size:12px; font-weight:800;">✓ Готово</button>
        </div>
      </div>
      <div class="apk-target-tabs" id="apkEditorTargetTabs"></div>
    `;
    colEditorEl.insertBefore(editorTopBar, colEditorEl.firstChild);

    function refreshEditorTargetTabs() {
      const tabsBox = document.getElementById("apkEditorTargetTabs");
      if (!tabsBox) return;
      const cellPart = state.selected.type === "cell"
        ? `<button type="button" class="apk-target-tab active" data-t="cell" data-id="${state.selected.id}">⬡ Клетка #${state.selected.id}</button>`
        : "";
      const slotBtns = [1, 2, 3].map((sid) => {
        const isAct = state.selected.type === "wait" && state.selected.id === sid;
        const cnt = (state.waits[sid] || []).reduce((a, b) => a + b.count, 0);
        return `<button type="button" class="apk-target-tab${isAct ? " active" : ""}" data-t="wait" data-id="${sid}">${window.APK_SLOT_LABELS[sid]}${cnt ? " (" + cnt + ")" : ""}</button>`;
      }).join("");
      tabsBox.innerHTML = cellPart + slotBtns;
      tabsBox.querySelectorAll(".apk-target-tab").forEach((b) => {
        b.addEventListener("click", () => {
          const t = b.getAttribute("data-t");
          const id = parseInt(b.getAttribute("data-id"), 10);
          state.selected = { type: t, id };
          renderAll();
        });
      });
    }

    // 3. Build Slide-Up Sheets for Log, Menu/Saves, FAQ Guide, and Board Diff Approval
    const logModal = document.createElement("div");
    logModal.id = "apkLogModal";
    logModal.className = "apk-modal-sheet";
    logModal.innerHTML = `
      <div class="apk-sheet-handle-zone" data-sheet-handle="1" title="Потяните вниз, чтобы скрыть">
        <div class="apk-sheet-handle"></div>
      </div>
      <div class="apk-modal-hdr" style="padding-top:2px;">
        <div class="apk-modal-title">📜 Журнал ходов и скрины действий</div>
        <button type="button" data-close-sheet="1" style="padding:4px 10px; font-size:12px;">✕ Закрыть</button>
      </div>
      <div class="apk-modal-body" id="apkLogModalBody">
        <div class="apk-log-quickbar">
          <button type="button" id="btnApkBigDownloadLog" class="apk-log-dl-big">⬇ Скачать лог + скрины (.txt)</button>
          <button type="button" id="btnApkBigHtmlLog" class="apk-log-copy-big" title="Открыть одним HTML в браузере">🌐 .html</button>
          <button type="button" id="btnApkBigCopyLog" class="apk-log-copy-big">📋 Текст</button>
          <button type="button" id="btnApkBigGithubLog" class="apk-log-copy-big" title="Отправить zip-лог со скринами в репозиторий GitHub">📤 GitHub</button>
        </div>
        <div id="githubUploadStatus" style="font-size:11.5px; line-height:1.4; color:#94a3b8; word-break:break-all; margin-bottom:6px;"></div>
      </div>
    `;
    document.body.appendChild(logModal);
    const actionLogSecEl = document.getElementById("actionLogSection");
    el.actionLogSection = actionLogSecEl;
    if (actionLogSecEl) {
      document.getElementById("apkLogModalBody").appendChild(actionLogSecEl);
    }

    function buildLogArchiveJsonPayload() {
      return JSON.stringify({
        stageNo: state.stageNo,
        curScore: state.curScore,
        solveMode: (el.solveModeSelect && el.solveModeSelect.value) ? el.solveModeSelect.value : "balanced",
        rotationDeg: state.rotationDeg || 0,
        currentSnapshot: captureProgressSnapshot(),
        entries: state.actionLog.map((it) => ({
          seq: it.seq,
          time: it.time || "",
          fullTs: it.fullTs || it.time || "",
          cat: it.cat || "cv",
          tag: it.tag || "ИНФО",
          plain: it.plain || "",
          subLinesPlain: Array.isArray(it.subLinesPlain) ? it.subLinesPlain : [],
          screenId: it.screenId || "",
          uiScreenId: it.uiScreenId || "",
          checkpoint: it.checkpoint || undefined,
        })),
      });
    }

    function triggerNativeOrFallbackLogDownload() {
      const baseName = `Лог_Скрины_Двойная_радость_Этап${state.stageNo}`;
      if (window.AndroidHost && typeof window.AndroidHost.exportLogZipBundle === "function") {
        if (window.AndroidHost.exportLogZipBundle(baseName, buildLogArchiveJsonPayload())) return;
      }
      if (window.AndroidHost && typeof window.AndroidHost.exportLogHtmlBundle === "function") {
        if (window.AndroidHost.exportLogHtmlBundle(baseName, buildLogArchiveJsonPayload())) return;
      }
      const text = buildFullPlainLogText();
      const fName = `Лог_Двойная_радость_Этап${state.stageNo}.txt`;
      if (window.AndroidHost && typeof window.AndroidHost.saveExportFile === "function") {
        if (window.AndroidHost.saveExportFile(fName, text, "text/plain")) return;
      }
      if (el.btnDownloadLog) el.btnDownloadLog.click();
    }
    window.apkTriggerLogZipExport = triggerNativeOrFallbackLogDownload;

    document.getElementById("btnApkBigDownloadLog").addEventListener("click", triggerNativeOrFallbackLogDownload);
    document.getElementById("btnApkBigHtmlLog").addEventListener("click", () => {
      const baseName = `Лог_Скрины_Двойная_радость_Этап${state.stageNo}`;
      if (window.AndroidHost && typeof window.AndroidHost.exportLogHtmlBundle === "function") {
        window.AndroidHost.exportLogHtmlBundle(baseName, buildLogArchiveJsonPayload());
      }
    });
    document.getElementById("btnApkBigCopyLog").addEventListener("click", async () => {
      const text = buildFullPlainLogText();
      const cpBtn = document.getElementById("btnApkBigCopyLog");
      if (window.AndroidHost && typeof window.AndroidHost.copyToClipboard === "function") {
        if (window.AndroidHost.copyToClipboard(text)) {
          cpBtn.textContent = "✓ Скопировано!";
          setTimeout(() => { cpBtn.textContent = "📋 Текст"; }, 1400);
          return;
        }
      }
      try {
        await navigator.clipboard.writeText(text);
        cpBtn.textContent = "✓ Скопировано!";
        setTimeout(() => { cpBtn.textContent = "📋 Текст"; }, 1400);
      } catch (_) {}
    });

    // --- 📤 Отправка лога на GitHub (Contents API + PAT из localStorage) ---
    const GH_PAT_KEY = "double_joy_apk_gh_pat_v1";
    const GH_REPO_KEY = "double_joy_apk_gh_repo_v1";
    const GH_BRANCH_KEY = "double_joy_apk_gh_branch_v1";
    const GH_DEFAULT_REPO = "Slim-Sam/Double-joy";
    const GH_DEFAULT_BRANCH = "master";
    const GH_BUILTIN_PAT = ""; // ← встроенный PAT: приложение не спрашивает у друзей
    function ghSaveSettings() {
      try {
        const pt = document.getElementById("inpGhPat");
        const rp = document.getElementById("inpGhRepo");
        const br = document.getElementById("inpGhBranch");
        if (pt) localStorage.setItem(GH_PAT_KEY, pt.value.trim());
        if (rp) localStorage.setItem(GH_REPO_KEY, rp.value.trim() || GH_DEFAULT_REPO);
        if (br) localStorage.setItem(GH_BRANCH_KEY, br.value.trim() || GH_DEFAULT_BRANCH);
      } catch (_) {}
    }
    function ghSettings() {
      ghSaveSettings();
      let pat = "", repo = GH_DEFAULT_REPO, branch = GH_DEFAULT_BRANCH;
      try {
        pat = localStorage.getItem(GH_PAT_KEY) || GH_BUILTIN_PAT || "";
        repo = localStorage.getItem(GH_REPO_KEY) || GH_DEFAULT_REPO;
        branch = localStorage.getItem(GH_BRANCH_KEY) || GH_DEFAULT_BRANCH;
      } catch (_) {}
      return { pat: pat, repo: repo, branch: branch };
    }
    (function setupGithubUpload() {
      const btn = document.getElementById("btnApkBigGithubLog");
      const st = document.getElementById("githubUploadStatus");
      if (!btn || !st) return;
      let pollTimer = null;
      btn.addEventListener("click", () => {
        const gh = ghSettings();
        if (!gh.pat) {
          st.style.color = "#fbbf24";
          st.textContent = "⚠ Не задан GitHub PAT — открой 💾 Сейвы и настройки";
          return;
        }
        if (!(window.AndroidHost && typeof window.AndroidHost.startGithubUploadJson === "function")) {
          st.style.color = "#f87171";
          st.textContent = "❌ bridge отсутствует — нужна новая сборка APK";
          return;
        }
        if (pollTimer) return;
        const baseName = `Лог_Скрины_Двойная_радость_Этап${state.stageNo}`;
        let started = false;
        try {
          started = window.AndroidHost.startGithubUploadJson(
              baseName, buildLogArchiveJsonPayload(), gh.repo, gh.branch, "uploads", gh.pat);
        } catch (_) { started = false; }
        if (!started) {
          st.style.color = "#fbbf24";
          st.textContent = "⏳ Уже идёт отправка…";
          return;
        }
        btn.disabled = true;
        btn.style.opacity = "0.6";
        st.style.color = "#93c5fd";
        st.textContent = "📤 Отправка…";
        const t0 = Date.now();
        pollTimer = setInterval(() => {
          let s = {};
          try {
            s = JSON.parse((window.AndroidHost && window.AndroidHost.getGithubUploadStatusJson)
              ? window.AndroidHost.getGithubUploadStatusJson() : "{}");
          } catch (_) {}
          if (s.state === "ok") {
            clearInterval(pollTimer); pollTimer = null;
            btn.disabled = false; btn.style.opacity = "";
            st.style.color = "#34d399";
            st.textContent = "✅ " + (s.message || "Загружено") + (s.url ? " · " + s.url : "");
            // После УСПЕШНОЙ отправки очищаем журнал в приложении (правило пользователя)
            try {
              var _clrBtn = document.getElementById("btnClearLog");
              if (_clrBtn) { _clrBtn.click(); st.textContent += " · журнал очищен"; }
            } catch (_) {}
          } else if (s.state === "error") {
            clearInterval(pollTimer); pollTimer = null;
            btn.disabled = false; btn.style.opacity = "";
            st.style.color = "#f87171";
            st.textContent = "❌ " + (s.message || "Ошибка отправки");
          } else if (Date.now() - t0 > 300000) {
            clearInterval(pollTimer); pollTimer = null;
            btn.disabled = false; btn.style.opacity = "";
            st.style.color = "#f87171";
            st.textContent = "❌ Таймаут отправки (5 мин)";
          } else {
            st.textContent = "📤 " + (s.message || "Отправка…");
          }
        }, 700);
      });
    })();

    const faqModal = document.createElement("div");
    faqModal.id = "apkFaqModal";
    faqModal.className = "apk-modal-sheet";
    faqModal.innerHTML = `
      <div class="apk-sheet-handle-zone" data-sheet-handle="1" title="Потяните вниз, чтобы скрыть">
        <div class="apk-sheet-handle"></div>
      </div>
      <div class="apk-modal-hdr" style="padding-top:2px;">
        <div class="apk-modal-title">📘 Гайд по приложению (FAQ)</div>
        <button type="button" data-close-sheet="1" style="padding:4px 10px; font-size:12px;">✕ Закрыть</button>
      </div>
      <div class="apk-modal-body" id="apkFaqModalBody">
        <div class="apk-crest-showcase-card" style="margin-bottom:10px; padding:8px;">
          <img src="${window.APK_SUD_CREST_URI}" style="width:min(68vw, 220px); border-radius:9px;" alt="[SUD] Suicide Squad" />
        </div>
        <div class="apk-faq-card">
          <div class="apk-faq-card-title">🚀 1. Что это за приложение и первый запуск</div>
          <div class="apk-faq-card-body">
            Приложение помогает проходить игру <b>«Двойная радость»</b>. Оно следит за полем, показывает куда ставить стопки, проверяет что всё правильно и помогает не запутаться в ходах.<br/>
            <b>Главная мысль:</b> приложение — <b>помощник</b>, а не игра. Вы всё равно делаете ходы в игре сам, приложение лишь показывает что и куда делать.<br/><br/>
            <b>Первый запуск:</b><br/>
            1) Откройте приложение — появится главный экран с кнопками и картой поля.<br/>
            2) Выберите <b>Этап</b> вверху (например, 8). Приложение питается именно этапом — по нему считает target и замки.<br/>
            3) Нажмите <span class="apk-faq-kbd">🚀 Оверлей</span> — запустится плавающее окно поверх игры.<br/>
            <b>Не пугайтесь количества кнопок</b> — ниже каждая объяснена.
          </div>
        </div>
        <div class="apk-faq-card">
          <div class="apk-faq-card-title">🎮 2. Порядок игры: что нажимать и в каком порядке</div>
          <div class="apk-faq-card-body">
            <b>Простая последовательность для одного этапа:</b><br/>
            1. <span class="apk-faq-kbd">🚀 Оверлей</span> — запускаете плавающее окно.<br/>
            2. <span class="apk-faq-kbd">✋ Рука</span> — приложение считывает <b>текущую руку</b> (3 стопки) и, если поле ещё пустое, <b>стартовые башни</b> на доске.<br/>
            3. Делайте ходы в игре — ставьте стопки в клетки, как подсказывает оверлей (кружки 1 → 2 → 3 на доске).<br/>
            4. <span class="apk-faq-kbd">⏩ Шаг+Рука</span> — когда в игре появятся новые 3 стопки (новый раунд), жмите эту кнопку. Приложение пересчитает следующие ходы.<br/>
            5. Повторяйте шаги 3–4 до конца этапа.<br/><br/>
            <b>Простое правило:</b><br/>
            • <span class="apk-faq-kbd">✋ Рука</span> = «дай мне новую руку» (или стартовое поле).<br/>
            • <span class="apk-faq-kbd">⏩ Шаг+Рука</span> = «сделан этот раунд, дай следующий».<br/>
            • <span class="apk-faq-kbd">🚀 Оверлей</span> — запуск оверлея, если он свернулся или закрылся.
          </div>
        </div>
        <div class="apk-faq-card">
          <div class="apk-faq-card-title">🖱️ 3. Что делает каждая кнопка</div>
          <div class="apk-faq-card-body">
            <b>✋ Рука</b> — считывает текущую руку и стартовое поле.<br/>
            <b>⏩ Шаг+Рука</b> — признаёт что раунд сделан, переходит к следующему. При этом <b>автоматически проверяет счёт</b>: если реальный счёт в игре не совпадает с расчётным — появится предупреждение <span class="apk-faq-kbd">⚠ Счёт игры ≠ расчёту! Нажмите «? Сверить»</span>.<br/><br/>
            <b>? Сверить / 🔍 Сверка</b> — проверка синхронизации. Приложение сфотографирует игру и сравнит с моделью.<br/>
            • Если всё совпадает — скажет <b>✅ Поле совпадает с игрой</b>.<br/>
            • Если отличается — выведет список клеток и кнопку <b>✅ Привести к игре</b>.<br/><br/>
            <b>🔄 Досканировать ракурс</b> — если клетка заслонена (передняя башня закрывает нижние слои): приложение подсветит <span class="apk-faq-kbd">🔄 #клетка</span>, подскажет куда смахнуть поле, и после поворота жмите эту кнопку — угол определится автоматически и оба ракурса объединятся.<br/><br/>
            <b>↻ Обн.</b> — мгновенная привязка координат. Если окно перескочило или игру раздвинули — жмите <span class="apk-faq-kbd">↻ Обн.</span>.<br/>
            <b>🔄 0° (ракурс)</b> — показывает текущий угол. Поворот <b>не переключается автоматически</b> — только ручной. Это защищает от ложных поворотов на симметричных этапах.<br/><br/>
            <b>↗ / − / ✕</b> — открыть полное окно / свернуть в значок 🎯 / закрыть оверлей.
          </div>
        </div>
        <div class="apk-faq-card">
          <div class="apk-faq-card-title">🔄 4. Оверлей: что это и как управлять</div>
          <div class="apk-faq-card-body">
            Оверлей — плавающее окно поверх игры, разделённое на <b>3 зоны</b>:<br/>
            1. <b>Верхняя шапка</b> — кнопки (↻ Обн., AR ⇄, Поле ▼, ↗, −, ✕). Не закрывает клетки поля.<br/>
            2. <b>AR-кружки на доске</b> — кружки <b>1</b> (изумрудный), <b>2</b> (голубой), <b>3</b> (янтарный) в центрах целевых гексов. Слой прозрачен — перетаскивайте стопки прямо в кружки.<br/>
            3. <b>Нижняя полоска стопок</b> — жёстко держится над стопками руки, никогда не сползает на кнопки бустеров.<br/><br/>
            <b>Вид оверлея</b> (кнопка <span class="apk-faq-kbd">AR ⇄</span>):<br/>
            • <b>Комбо</b> — AR-кружки + нижняя полоска (по умолчанию).<br/>
            • <b>Только AR•1</b> — кружки + строка-памятка без полоски.<br/>
            • <b>Док</b> — компактное окно вверху без кружков.<br/><br/>
            <b>Мини-карта</b> (кнопка <span class="apk-faq-kbd">Поле ▼</span>):<br/>
            • Интерактивная карта поля под тем же углом, как в игре.<br/>
            • На каждой клетке — номер, высота башни (<span class="apk-faq-kbd">#3·×5</span>) и верхние цветные слои.<br/>
            • Целевые клетки подсвечены цветным контуром (<span class="apk-faq-kbd">→Ш1</span>, <span class="apk-faq-kbd">→Ш2</span>, <span class="apk-faq-kbd">→Ш3</span>).<br/>
            • Косниться клетки — прочитать её состав снизу вверх.<br/><br/>
            <b>Порядок карточек</b> (кнопка <span class="apk-faq-kbd">А ⇄ Б</span>):<br/>
            • <b>Режим А</b> — карточки строго над своей физической фишкой.<br/>
            • <b>Режим Б</b> — карточки по порядку хода: ШАГ 1 → 2 → 3.
          </div>
        </div>
        <div class="apk-faq-card">
          <div class="apk-faq-card-title">🛡️ 5. Бустеры: что делать, когда тяжело</div>
          <div class="apk-faq-card-body">
            <b>🔄 Перемешать</b> — если руки ведут в тупик:<br/>
            1. Жмите <span class="apk-faq-kbd">🔄 Перемешать</span> в игре.<br/>
            2. Сразу жмите <span class="apk-faq-kbd">✋ Рука</span> — приложение учтёт сделанный шаг автоматически.<br/><br/>
            <b>Умный СТОП:</b> приложение не заставит забивать последнюю клетку проигрышной пачкой.<br/>
            • Если 1–2 стопки дают слияние и очищают место, но остальные ведут в тупик — покажет только выгодный <b>ШАГ 1</b>, а на остальных <span class="apk-faq-kbd">⛔ СТОП → 🔄</span>.<br/>
            • Если ни одна из 3 пачек не сжигает блоки — заблокирует карточки <span class="apk-faq-kbd">⛔ СТОП</span> и подскажет жать <span class="apk-faq-kbd">🔄 Перемешать</span> или <span class="apk-faq-kbd">🔨 #клетка</span>.<br/><br/>
            <b>🔨 Молоток</b> — разбивает одну стопку на поле:<br/>
            1. Приложение предлагает, в какую клетку ударить (<span class="apk-faq-kbd">🔨 #N</span>).<br/>
            2. Ударьте в игре.<br/>
            3. Жмите <span class="apk-faq-kbd">? Сверить</span> → <span class="apk-faq-kbd">✓ Заменить</span> — приложение применит изменения.
          </div>
        </div>
        <div class="apk-faq-card">
          <div class="apk-faq-card-title">🔍 6. Сверка: как понять и исправить рассинхронизацию</div>
          <div class="apk-faq-card-body">
            Сверка — проверка «как на экране, как в приложении».<br/><br/>
            <b>Что делает сверка:</b><br/>
            1. Снимает кадр игры.<br/>
            2. Распознаёт ракурс и читает все клетки (включая скрытые под башнями).<br/>
            3. Сравнивает с моделью приложения.<br/>
            4. Выводит список отличий.<br/><br/>
            <b>Если поле совпадает</b> — появится <b>✅ Поле совпадает с игрой</b>, всё хорошо.<br/>
            <b>Если отличается:</b><br/>
            1. Появится список клеток с галочками <span class="apk-faq-kbd">☑</span>.<br/>
            2. Выберите клетки для исправления (можно <b>Все</b> / <b>Снять</b>).<br/>
            3. Жмите <b>✅ Привести к игре</b>.<br/>
            4. Приложение применит изменения: клетки, счёт и руку.<br/><br/>
            <b>Важно: подсказки не меняют поле.</b> Подсказки (например, <span class="apk-faq-kbd">🔨 Примени молоток</span>) — это только <b>предложение</b>. Пока не применены — поле остаётся как есть. Поле изменится только когда вы примените подсказку.
          </div>
        </div>
        <div class="apk-faq-card" style="border-color:#f59e0b;">
          <div class="apk-faq-card-title">⚠️ 7. Если ты ошибся: стопку поставил не туда, перепутал шаги</div>
          <div class="apk-faq-card-body">
            Ошибки случаются — это не катастрофа. <b>Важное правило: не пытайтесь «подогнать» игру под ошибку — сначала исправьте синхронизацию, потом продолжайте.</b><br/><br/>
            <b>Что делать, если поняли что стопку поставили не туда:</b><br/>
            1. <b>Не ставьте следующие стопки.</b> Остановитесь.<br/>
            2. <b>Нажмите ? Сверить (или 🔍 Сверка).</b> Приложение покажет, где расхождение.<br/>
            3. <b>Убедитесь что совпадает счёт.</b><br/>
            4. <b>Выберите клетки для исправления</b> — отметьте галочками ☑.<br/>
            5. <b>Нажмите ✅ Привести к игре.</b><br/>
            6. <b>Проверьте ✅ Поле совпадает с игрой</b> — теперь модель и игра в одном положении.<br/>
            7. <b>Продолжайте игру</b> — жмите <span class="apk-faq-kbd">⏩ Шаг+Рука</span>.<br/><br/>
            <b>Если предложение кажется странным (ставит на занятую клетку):</b><br/>
            Это <b>не ошибка программы</b>, а следствие рассинхронизации — модель «запазывает».<br/>
            1. <b>Не ставьте стопку туда, куда предлагает программа</b>, если клетка на экране занята.<br/>
            2. <b>Нажмите ? Сверить</b> — приложение покажет реальное состояние.<br/>
            3. <b>Выберите галочки ☑</b> и жмите <b>✅ Привести к игре</b>.<br/>
            4. <b>Убедитесь в ✅ Поле совпадает с игрой.</b><br/>
            5. <b>Теперь предложение пересчитается</b> — приложение предложит ставить на правильную, свободную клетку.<br/><br/>
            <b>Простой принцип:</b> если предложение кажется неправильным — это <b>сигнал</b>, что нужна <b>сверка</b>, а не ставка стопки. Исправляйте синхронизацию сначала.
          </div>
        </div>
        <div class="apk-faq-card">
          <div class="apk-faq-card-title">⚙️ 8. Настройки, которые стоит знать</div>
          <div class="apk-faq-card-body">
            <b>🧠 Режим расчёта</b> — как глубоко приложение думает:<br/>
            • <b>⚡ Быстрый</b> — для лёгких этапов 1–2.<br/>
            • <b>⚖️ Оптимальный</b> — по умолчанию.<br/>
            • <b>🛡 Выживание</b> — для тесного поля с замками.<br/>
            • <b>🔬 Максимальный</b> — для сложных стартов и концовок.<br/>
            Приложение <b>само предлагает</b> режим при сканировании.<br/><br/>
            <b>🔍 Масштаб интерфейса</b> — если что-то не помещается: Меню → <span class="apk-faq-kbd">−</span> / <span class="apk-faq-kbd">Авто</span> / <span class="apk-faq-kbd">+</span>.<br/><br/>
            <b>💡 Учитывать подсказки при расчёте</b> — по умолчанию <b>ВЫКЛ</b>: игнорируются <b>только игровые подсказки</b> (🔨 молоток, 🔄 обновить, переместить столбик) — <b>а всё, что предлагает приложение</b> (планы, предупреждения о тупике, советы режимов), <b>работает всегда</b>. ВКЛ — показываются советы по бустерам (🔨 молоток, 🔄 обновить — <b>только если бустер реально есть в наличии</b>, приложение проверяет панель бустеров по скрину) и учитываются применённые игровые подсказки.<br/><br/>
            <b>📷 Сохранять скриншот с интерфейсом</b> — по умолчанию <b>ВЫКЛ</b>. Сохраняется только чистый игровой кадр. ВКЛ — также сохраняется кадр с оверлейом (полезно для диагностики).
          </div>
        </div>
        <div class="apk-faq-card" style="margin-bottom:2px;">
          <div class="apk-faq-card-title">📜 9. Лог и полезные мелочи</div>
          <div class="apk-faq-card-body">
            <b>📜 Лог</b> — журнал всех действий. Каждая запись имеет номер <span class="apk-faq-kbd">#N</span> и сохранённый скриншот:<br/>
            • <span class="apk-faq-kbd">📸 Скрин ▼</span> — посмотреть кадр.<br/>
            • <span class="apk-faq-kbd">⬇ Скачать лог + скрины (.txt)</span> — выгрузить всё в один файл.<br/>
            • <span class="apk-faq-kbd">🌐 .html</span> — сохранить страницу в браузере.<br/>
            • <span class="apk-faq-kbd">↺ Откат сюда</span> — вернуть поле, руку и счёт к выбранному раунду.<br/><br/>
            <b>💾 Меню</b> — сейвы и настройки: режим расчёта, масштаб, новые тумблеры подсказок и UI-скринов.<br/><br/>
            <b>Офлайн 100%:</b> приложение работает полностью на устройстве и не требует интернета.
          </div>
        </div>
      </div>
    `;
    document.body.appendChild(faqModal);

    const menuModal = document.createElement("div");
    menuModal.id = "apkMenuModal";
    menuModal.className = "apk-modal-sheet";
    menuModal.innerHTML = `
      <div class="apk-sheet-handle-zone" data-sheet-handle="1" title="Потяните вниз, чтобы скрыть">
        <div class="apk-sheet-handle"></div>
      </div>
      <div class="apk-modal-hdr" style="padding-top:2px;">
        <div class="apk-modal-title">💾 Сейвы и настройки (v1.0.38)</div>
        <button type="button" data-close-sheet="1" style="padding:4px 10px; font-size:12px;">✕ Закрыть</button>
      </div>
      <div class="apk-modal-body" id="apkMenuModalBody">
        <div style="display:grid; grid-template-columns:1fr 1fr; gap:7px; margin-bottom:10px;">
          <button type="button" id="btnApkResetStageAction" class="btn-warning" style="padding:8px; font-weight:800;">🔄 Сброс этапа в 0</button>
          <button type="button" id="btnApkClearBoardAction" class="btn-danger-soft" style="padding:8px; font-weight:800;">🧹 Очистить поле</button>
        </div>
        <div style="background:#172136; border:1px solid #334155; border-radius:9px; padding:8px 9px; margin-bottom:10px;">
          <div style="display:flex; align-items:center; justify-content:space-between; gap:8px;">
            <span style="font-size:12px; font-weight:700; color:#cbd5e1;">🔍 Масштаб интерфейса:</span>
            <div style="display:inline-flex; align-items:center; gap:5px;">
              <button type="button" id="btnApkZoomDec" style="padding:3px 10px; font-weight:900;">−</button>
              <button type="button" id="btnApkZoomReset" style="padding:3px 9px; font-size:11.5px; font-weight:800; min-width:74px;">Авто (100%)</button>
              <button type="button" id="btnApkZoomInc" style="padding:3px 10px; font-weight:900;">+</button>
            </div>
          </div>
        </div>
        <div style="background:#172136; border:1px solid #334155; border-radius:9px; padding:8px 9px; margin-bottom:10px;">
          <div id="apkSolverModeHost" style="display:flex; align-items:center; justify-content:space-between; gap:8px;">
            <span style="font-size:12px; font-weight:700; color:#cbd5e1; white-space:nowrap;">🧠 Режим расчёта:</span>
          </div>
          <div id="apkSolverModeDesc" style="font-size:11px; color:#94a3b8; line-height:1.38; margin-top:6px; padding-top:5px; border-top:1px solid rgba(51,65,85,0.65);"></div>
        </div>
        <div style="background:#172136; border:1px solid #334155; border-radius:9px; padding:8px 9px; margin-bottom:10px;">
          <div style="display:flex; align-items:center; justify-content:space-between; gap:8px;">
            <span style="font-size:12px; font-weight:700; color:#cbd5e1;">💡 Учитывать подсказки при расчёте:</span>
            <label style="position:relative; display:inline-block; width:38px; height:22px; cursor:pointer;">
              <input type="checkbox" id="chkUseHintsInCalc" style="opacity:0; width:0; height:0;" />
              <span id="chkUseHintsInCalcThumb" style="position:absolute; inset:0; background:#334155; border-radius:22px; transition:0.2s; pointer-events:none;"></span>
            </label>
          </div>
          <div style="font-size:10.5px; color:#64748b; line-height:1.35; margin-top:5px; padding-top:5px; border-top:1px solid rgba(51,65,85,0.65);">OFF (по умолчанию) — игнорируются ТОЛЬКО игровые подсказки (🔨 молоток, 🔄 обновить, переместить столбик). Все советы приложения (план, предупреждения о тупике, режимы) работают всегда. ВКЛ — советы по бустерам (🔨/🔄/переместить — только при наличии бустера) и применённые игровые подсказки.</div>
        </div>
        <div style="background:#172136; border:1px solid #334155; border-radius:9px; padding:8px 9px; margin-bottom:10px;">
          <div style="display:flex; align-items:center; justify-content:space-between; gap:8px;">
            <span style="font-size:12px; font-weight:700; color:#cbd5e1;">📷 Сохранять скриншот с интерфейсом:</span>
            <label style="position:relative; display:inline-block; width:38px; height:22px; cursor:pointer;">
              <input type="checkbox" id="chkSaveUiScreenshots" style="opacity:0; width:0; height:0;" />
              <span id="chkSaveUiScreenshotsThumb" style="position:absolute; inset:0; background:#334155; border-radius:22px; transition:0.2s; pointer-events:none;"></span>
            </label>
          </div>
          <div style="font-size:10.5px; color:#64748b; line-height:1.35; margin-top:5px; padding-top:5px; border-top:1px solid rgba(51,65,85,0.65);">OFF — сохраняется только чистый игровой кадр, без оверлейных панелей и кнопок.</div>
        </div>
        <div style="background:#172136; border:1px solid #334155; border-radius:9px; padding:8px 9px; margin-bottom:10px;">
          <div style="font-size:12px; font-weight:700; color:#cbd5e1; margin-bottom:6px;">📤 Отправка логов на GitHub</div>
          <div style="display:grid; gap:5px;">
            <input type="password" id="inpGhPat" placeholder="GitHub PAT (Contents: Read and write)" autocomplete="off" spellcheck="false"
              style="width:100%; box-sizing:border-box; background:#0f172a; border:1px solid #334155; border-radius:7px; color:#e2e8f0; padding:7px 8px; font-size:12px;" />
            <div style="display:grid; grid-template-columns:2fr 1fr; gap:5px;">
              <input type="text" id="inpGhRepo" placeholder="Slim-Sam/Double-joy" autocomplete="off" spellcheck="false"
                style="width:100%; box-sizing:border-box; background:#0f172a; border:1px solid #334155; border-radius:7px; color:#e2e8f0; padding:7px 8px; font-size:12px;" />
              <input type="text" id="inpGhBranch" placeholder="master" autocomplete="off" spellcheck="false"
                style="width:100%; box-sizing:border-box; background:#0f172a; border:1px solid #334155; border-radius:7px; color:#e2e8f0; padding:7px 8px; font-size:12px;" />
            </div>
          </div>
          <div style="font-size:10.5px; color:#64748b; line-height:1.35; margin-top:5px; padding-top:5px; border-top:1px solid rgba(51,65,85,0.65);">✅ PAT предустановлен в приложении — вводить не нужно: сразу 📜 Журнал → 📤 GitHub (zip со скринами → uploads/). Токен встроен и в архив лога не попадает; поле выше — только для замены собственного токена.</div>
        </div>
      </div>
    `;
    document.body.appendChild(menuModal);
    (function bindGithubSettings() {
      const pt = document.getElementById("inpGhPat");
      const rp = document.getElementById("inpGhRepo");
      const br = document.getElementById("inpGhBranch");
      if (!pt || !rp || !br) return;
      try {
        pt.value = localStorage.getItem(GH_PAT_KEY) || GH_BUILTIN_PAT || "";
        rp.value = localStorage.getItem(GH_REPO_KEY) || GH_DEFAULT_REPO;
        br.value = localStorage.getItem(GH_BRANCH_KEY) || GH_DEFAULT_BRANCH;
      } catch (_) {}
      pt.addEventListener("change", ghSaveSettings);
      rp.addEventListener("change", ghSaveSettings);
      br.addEventListener("change", ghSaveSettings);
    })();
    document.getElementById("apkSolverModeHost").appendChild(el.solveModeSelect);
    document.getElementById("apkMenuModalBody").appendChild(el.saveLoadDrawer);
    el.saveLoadDrawer.classList.remove("hidden");

    const SOLVE_MODE_HINTS = {
      fast: "⚡ Мгновенный поиск (55 лучей, без долгого прогноза будущей руки) — для быстрых простых этапов.",
      balanced: "⚖️ Баланс очков, комбо и прогноза на 14 будущих раздач (160 лучей) — оптимально для постоянной игры.",
      survival: "🛡 Усиленная расчистка башен и сохранение пустых клеток ×1.35 (220 лучей) — когда на поле тесно (≤ 3–4 клеток).",
      deep: "🔬 Максимальная глубина перебора (340 лучей + двойной прогноз по 24 финалистам) — для сложных концовок.",
    };
    const SOLVE_MODE_STORAGE_KEY = "double_joy_apk_solve_mode_v1";

    function updateSolveModeDesc() {
      const descEl = document.getElementById("apkSolverModeDesc");
      if (!descEl || !el.solveModeSelect) return;
      const m = el.solveModeSelect.value || "balanced";
      descEl.textContent = SOLVE_MODE_HINTS[m] || SOLVE_MODE_HINTS.balanced;
    }

    try {
      const savedMode = localStorage.getItem(SOLVE_MODE_STORAGE_KEY);
      if (savedMode && SOLVE_MODE_HINTS[savedMode] && el.solveModeSelect) {
        el.solveModeSelect.value = savedMode;
        syncStateToAndroidHost();
      }
    } catch (_) {}
    updateSolveModeDesc();

    if (el.solveModeSelect) {
      el.solveModeSelect.addEventListener("change", () => {
        try {
          localStorage.setItem(SOLVE_MODE_STORAGE_KEY, el.solveModeSelect.value);
        } catch (_) {}
        updateSolveModeDesc();
        syncStateToAndroidHost();
      });
    }

    const UI_ZOOM_STORAGE_KEY = "double_joy_apk_ui_zoom_v1";
    let apkUiZoomPct = 100;
    try {
      const savedZ = parseInt(localStorage.getItem(UI_ZOOM_STORAGE_KEY) || "100", 10);
      if (savedZ >= 75 && savedZ <= 130) apkUiZoomPct = savedZ;
    } catch (_) {}

    function applyApkUiZoom() {
      document.documentElement.style.zoom = apkUiZoomPct === 100 ? "" : `${apkUiZoomPct}%`;
      const zBtn = document.getElementById("btnApkZoomReset");
      if (zBtn) zBtn.textContent = apkUiZoomPct === 100 ? "Авто (100%)" : `${apkUiZoomPct}%`;
      try { localStorage.setItem(UI_ZOOM_STORAGE_KEY, String(apkUiZoomPct)); } catch (_) {}
    }
    applyApkUiZoom();

    document.getElementById("btnApkZoomDec").addEventListener("click", () => {
      apkUiZoomPct = Math.max(75, apkUiZoomPct - 5);
      applyApkUiZoom();
    });
    document.getElementById("btnApkZoomReset").addEventListener("click", () => {
      apkUiZoomPct = 100;
      applyApkUiZoom();
    });
    document.getElementById("btnApkZoomInc").addEventListener("click", () => {
      apkUiZoomPct = Math.min(130, apkUiZoomPct + 5);
      applyApkUiZoom();
    });

    // --- Toggle: Учитывать подсказки при расчёте ---
    const HINTS_STORAGE_KEY = "double_joy_apk_use_hints_v1";
    try {
      apkUi.useHintsInCalc = localStorage.getItem(HINTS_STORAGE_KEY) === "1";
    } catch (_) {}
    function applyUseHintsToggle() {
      const chk = document.getElementById("chkUseHintsInCalc");
      const thumb = document.getElementById("chkUseHintsInCalcThumb");
      if (chk) chk.checked = apkUi.useHintsInCalc;
      if (thumb) {
        thumb.style.background = apkUi.useHintsInCalc ? "#10b981" : "#334155";
        thumb.style.transform = apkUi.useHintsInCalc ? "translateX(16px)" : "translateX(0)";
      }
      try { localStorage.setItem(HINTS_STORAGE_KEY, apkUi.useHintsInCalc ? "1" : "0"); } catch (_) {}
      if (window.AndroidHost && typeof window.AndroidHost.setUseHintsInCalc === "function") {
        try { window.AndroidHost.setUseHintsInCalc(apkUi.useHintsInCalc); } catch (_) {}
      }
    }
    applyUseHintsToggle();
    document.getElementById("chkUseHintsInCalc").addEventListener("change", (e) => {
      apkUi.useHintsInCalc = e.target.checked;
      applyUseHintsToggle();
    });

    // --- Toggle: Сохранять скриншот с интерфейсом ---
    const UI_SCREENSHOT_STORAGE_KEY = "double_joy_apk_save_ui_screenshots_v1";
    try {
      apkUi.saveUiScreenshots = localStorage.getItem(UI_SCREENSHOT_STORAGE_KEY) === "1";
    } catch (_) {}
    function applySaveUiScreenshotsToggle() {
      const chk = document.getElementById("chkSaveUiScreenshots");
      const thumb = document.getElementById("chkSaveUiScreenshotsThumb");
      if (chk) chk.checked = apkUi.saveUiScreenshots;
      if (thumb) {
        thumb.style.background = apkUi.saveUiScreenshots ? "#10b981" : "#334155";
        thumb.style.transform = apkUi.saveUiScreenshots ? "translateX(16px)" : "translateX(0)";
      }
      try { localStorage.setItem(UI_SCREENSHOT_STORAGE_KEY, apkUi.saveUiScreenshots ? "1" : "0"); } catch (_) {}
      if (window.AndroidHost && typeof window.AndroidHost.setSaveUiScreenshots === "function") {
        try { window.AndroidHost.setSaveUiScreenshots(apkUi.saveUiScreenshots); } catch (_) {}
      }
    }
    applySaveUiScreenshotsToggle();
    document.getElementById("chkSaveUiScreenshots").addEventListener("change", (e) => {
      apkUi.saveUiScreenshots = e.target.checked;
      applySaveUiScreenshotsToggle();
    });

    const diffModal = document.createElement("div");
    diffModal.id = "apkDiffModal";
    diffModal.className = "apk-modal-sheet";
    diffModal.innerHTML = `
      <div class="apk-sheet-handle-zone" data-sheet-handle="1" title="Потяните вниз, чтобы скрыть">
        <div class="apk-sheet-handle"></div>
      </div>
      <div class="apk-modal-hdr" style="padding-top:2px;">
        <div class="apk-modal-title">🔍 Сверка с игрой</div>
        <div style="display:flex; gap:4px; align-items:center; flex-wrap:wrap;">
          <button type="button" id="btnApkPickDiffShot" class="btn-primary" style="padding:4px 8px; font-size:11px; font-weight:800;">📂 Скрин</button>
          <button type="button" id="btnApkMergeDiffShot" style="padding:4px 8px; font-size:11px; font-weight:800; background:#0369a1; border-color:#7dd3fc; color:#fff;" title="Добавить скриншот второго ракурса после поворота доски">🔄 +2-й ракурс</button>
          <input type="file" id="apkDiffFileInput" accept="image/*" class="hidden" />
          <input type="file" id="apkDiffMergeInput" accept="image/*" class="hidden" />
          <button type="button" data-close-sheet="1" style="padding:4px 8px; font-size:12px;">✕</button>
        </div>
      </div>
      <div style="background:#0c4a6e; border:1px solid #0369a1; border-radius:8px; padding:7px 9px; margin:7px 9px 0; font-size:11px; color:#bae6fd; line-height:1.4;">
        <b>🔄 Вращение доски для сверки:</b> поле можно поворачивать на <b>0°, 60°, 120°, 180°, 240°, 300°</b>.<br/>
        • <b>0°</b> — основной ракурс, всегда используйте его первым.<br/>
        • Если клетка <b>заслонена</b> (передняя башня закрывает нижние слои) — приложение подсветит <span class="apk-faq-kbd">🔄 #клетка</span> и подскажет, куда смахнуть поле в игре (например, 2 щелчка влево/вправо).<br/>
        • После поворота в игре нажмите <span class="apk-faq-kbd">🔄 +2-й ракурс</span> или <span class="apk-faq-kbd">🔄 Досканировать ракурс</span> — приложение определит новый угол автоматически и объединит оба ракурса для 100% точности.<br/>
        • Поворот <b>не переключается автоматически</b> — только ручной (кнопка <span class="apk-faq-kbd">🔄 0°</span> под картой или свайп в игре). Это защищает от ложных поворотов на симметричных этапах.
      </div>
      <div class="apk-modal-body" id="apkDiffModalBody"></div>
      <div class="apk-modal-ftr" id="apkDiffModalFtr">
        <button type="button" id="btnApkConfirmDiff" class="btn-success" style="flex:1.5; padding:9px; font-size:13px; font-weight:900;">✅ Заменить выбранные</button>
        <button type="button" id="btnApkCancelDiff" class="btn-danger-soft" style="flex:0.8; padding:9px; font-size:12.5px; font-weight:800;">✕ Отмена</button>
      </div>
    `;
    document.body.appendChild(diffModal);

    const crestModal = document.createElement("div");
    crestModal.id = "apkCrestModal";
    crestModal.className = "apk-modal-sheet";
    crestModal.innerHTML = `
      <div class="apk-sheet-handle-zone" data-sheet-handle="1" title="Потяните вниз, чтобы скрыть">
        <div class="apk-sheet-handle"></div>
      </div>
      <div class="apk-modal-hdr" style="padding-top:2px;">
        <div class="apk-modal-title">🏆 "SUD" Suicide Squad — Двойная радость!</div>
        <button type="button" data-close-sheet="1" style="padding:4px 10px; font-size:12px;">✕ Закрыть</button>
      </div>
      <div class="apk-modal-body" id="apkCrestModalBody" style="display:flex; flex-direction:column; align-items:center; text-align:center;">
        <img src="${window.APK_SUD_CREST_URI}" style="width:min(90vw, 430px); height:auto; border-radius:14px; border:1.5px solid rgba(217,119,6,0.65); box-shadow:0 8px 28px rgba(0,0,0,0.75);" alt="[SUD] Suicide Squad — Двойная радость!" />
        <div style="margin-top:10px; font-size:12.5px; color:#fde68a; font-weight:800;">Специальное издание для клана [SUD] Suicide Squad (v1.0.38)</div>
      </div>
    `;
    document.body.appendChild(crestModal);

    // Mount Smart Solver Mode Advisor Bar right above #plansContainer
    const modeAdviceBar = document.createElement("div");
    modeAdviceBar.id = "apkModeAdviceBar";
    modeAdviceBar.className = "apk-mode-advice-bar hidden";
    if (el.plansContainer && el.plansContainer.parentNode) {
      el.plansContainer.parentNode.insertBefore(modeAdviceBar, el.plansContainer);
    }

    const MODE_TITLES_RU = {
      fast: "⚡ Быстрый",
      balanced: "⚖️ Оптимальный",
      survival: "🛡 Выживание",
      deep: "🔬 Максимальный",
    };

    function computeJsModeAdvice() {
      const curM = (el.solveModeSelect && el.solveModeSelect.value) || "balanced";
      const stg = getStageCfg(state.stageNo);
      const targetScore = stg ? stg.targetScore : 450;
      const plan = state.plans && state.plans[0] ? state.plans[0] : null;
      if (plan && plan.isWin) return null;

      let totalCells = 0, emptyNow = 0, activeLocks = 0, multiTowers = 0;
      for (let cid = 1; cid <= 42; cid++) {
        const c = state.cells[cid];
        if (!c || c.exist !== 1) continue;
        totalCells++;
        const lk = effectiveLocks(c, state.curScore);
        if (lk.isLocked) activeLocks++;
        else if (!c.layers || c.layers.length === 0) emptyNow++;
        else if (c.layers.length >= 2) multiTowers++;
      }

      if (window.apkLatestModeAdvice && window.apkLatestModeAdvice.suggestedMode && state.plans && state.plans.length > 0) {
        const ma = window.apkLatestModeAdvice;
        if (ma.suggestedMode !== curM) return ma;
      }

      if (plan && plan.steps && plan.steps.length > 0) {
        const finalEmpty = plan.finalEmptyCells;
        const walledCnt = (plan.walledWarnings || []).length;
        const mergeProb = (plan.nextHandForecast && plan.nextHandForecast.mergeProbNextHand) || 60;
        const progress = targetScore > 0 ? state.curScore / targetScore : 0;

        if ((finalEmpty >= 1 && finalEmpty <= 3) || (finalEmpty === 4 && (emptyNow <= 5 || walledCnt >= 2 || mergeProb < 42))) {
          if (curM === "fast" || curM === "balanced") {
            return {
              suggestedMode: "survival",
              suggestedModeTitleRu: MODE_TITLES_RU.survival,
              suggestedModeReasonRu: `⚠ Риск зажима поля (останется ${finalEmpty} пуст., сл.рука ${Math.round(mergeProb)}%) — заранее включите «🛡 Выживание»!`,
              urgent: true,
            };
          }
          if (curM === "survival" && (finalEmpty <= 2 || (finalEmpty === 3 && mergeProb < 38))) {
            return {
              suggestedMode: "deep",
              suggestedModeTitleRu: MODE_TITLES_RU.deep,
              suggestedModeReasonRu: `⚠ Поле на грани тупика (останется ${finalEmpty} пуст.) — включите «🔬 Максимальный» (340 лучей)!`,
              urgent: true,
            };
          }
        }
        if ((finalEmpty === 4 || finalEmpty === 5) && (emptyNow <= 6 || walledCnt >= 2 || multiTowers >= 7 || mergeProb < 44) && (curM === "fast" || curM === "balanced")) {
          return {
            suggestedMode: "survival",
            suggestedModeTitleRu: MODE_TITLES_RU.survival,
            suggestedModeReasonRu: `⚠ Места становится мало (пусто: ${finalEmpty}) — советуем заранее перейти на «🛡 Выживание»!`,
            urgent: true,
          };
        }
        if (targetScore >= 450 && progress >= 0.72 && finalEmpty <= 6 && multiTowers >= 5 && (curM === "fast" || curM === "balanced")) {
          return {
            suggestedMode: "deep",
            suggestedModeTitleRu: MODE_TITLES_RU.deep,
            suggestedModeReasonRu: `🔬 Сложная концовка (${state.curScore}/${targetScore}) — советуем «🔬 Максимальный»!`,
            urgent: true,
          };
        }
      }

      if (window.apkJustChangedStage || state.curScore === 0) {
        let ideal = "balanced";
        let reason = `💡 Этап ${state.stageNo} (просторное поле, цель ${targetScore}): оптимален «⚖️ Оптимальный».`;
        if (state.stageNo <= 2 || targetScore <= 180) {
          ideal = "fast";
          reason = `💡 Этап ${state.stageNo} — короткий блиц без замков (цель ${targetScore}): оптимален «⚡ Быстрый».`;
        } else if (state.stageNo === 13 || state.stageNo === 16) {
          ideal = "deep";
          reason = `💡 Этап ${state.stageNo} (стартовые 4-слойные башни + замки, цель ${targetScore}): рекомендуем «🔬 Максимальный».`;
        } else if (state.stageNo === 9 && activeLocks >= 3) {
          ideal = "survival";
          reason = `💡 Этап 9 (7 замков на поле!): рекомендуем «🛡 Выживание» до открытия замков.`;
        } else if (state.stageNo === 12 || (totalCells > 0 && totalCells <= 18 && targetScore >= 500)) {
          ideal = "survival";
          reason = `💡 Этап ${state.stageNo} (узкое поле ${totalCells} клеток, цель ${targetScore}): советуем «🛡 Выживание».`;
        } else if ((state.stageNo === 8 || state.stageNo === 15) && (activeLocks >= 2 || multiTowers >= 3)) {
          ideal = "survival";
          reason = `💡 Этап ${state.stageNo} (тесный старт: замки и башни): советуем «🛡 Выживание».`;
        }
        if (ideal !== curM && !(ideal === "survival" && curM === "deep")) {
          return {
            suggestedMode: ideal,
            suggestedModeTitleRu: MODE_TITLES_RU[ideal],
            suggestedModeReasonRu: reason,
            urgent: false,
          };
        }
      }
      return null;
    }

    function refreshApkModeAdviceUi() {
      if (!modeAdviceBar) return;
      const ma = computeJsModeAdvice();
      if (!ma || !ma.suggestedMode) {
        modeAdviceBar.classList.add("hidden");
        return;
      }
      const key = `${state.stageNo}:${ma.suggestedMode}:${ma.urgent ? 1 : 0}`;
      if (key === window.apkDismissedModeAdviceKey) {
        modeAdviceBar.classList.add("hidden");
        return;
      }
      modeAdviceBar.classList.remove("hidden");
      modeAdviceBar.classList.toggle("urgent", !!ma.urgent);
      modeAdviceBar.innerHTML = `
        <div style="flex:1; min-width:0;">${ma.suggestedModeReasonRu}</div>
        <div style="display:inline-flex; align-items:center; gap:4px; flex-shrink:0;">
          <button type="button" id="btnApkApplyModeAdvice" class="btn-success" style="padding:5px 8px; font-size:11px; font-weight:800; white-space:nowrap;">Вкл. ${ma.suggestedModeTitleRu}</button>
          <button type="button" id="btnApkDismissModeAdvice" style="padding:4px 7px; font-size:11px;">✕</button>
        </div>
      `;
      document.getElementById("btnApkApplyModeAdvice").addEventListener("click", () => {
        if (el.solveModeSelect) {
          el.solveModeSelect.value = ma.suggestedMode;
          try { localStorage.setItem(SOLVE_MODE_STORAGE_KEY, ma.suggestedMode); } catch (_) {}
          updateSolveModeDesc();
          window.apkDismissedModeAdviceKey = key;
          syncStateToAndroidHost();
          runSolver();
          refreshApkModeAdviceUi();
        }
      });
      document.getElementById("btnApkDismissModeAdvice").addEventListener("click", () => {
        window.apkDismissedModeAdviceKey = key;
        modeAdviceBar.classList.add("hidden");
      });
    }

    el.stageSelect.addEventListener("change", () => {
      window.apkJustChangedStage = true;
      window.apkDismissedModeAdviceKey = "";
      setTimeout(refreshApkModeAdviceUi, 30);
    });

    const allSheetEls = [colEditorEl, logModal, menuModal, diffModal, faqModal, crestModal];
    let sheetAnimCleanupTimer = null;

    function clearSheetDragInlineStyles() {
      if (sheetAnimCleanupTimer) {
        clearTimeout(sheetAnimCleanupTimer);
        sheetAnimCleanupTimer = null;
      }
      backdrop.style.opacity = "";
      backdrop.style.transition = "";
      allSheetEls.forEach((s) => {
        if (!s) return;
        s.classList.remove("is-dragging");
        s.style.transform = "";
        s.style.transition = "";
      });
    }

    function openSheet(which) {
      clearSheetDragInlineStyles();
      closeAllSheets();
      apkUi.activeSheet = which;
      backdrop.classList.add("open");
      // Lock background scroll when a sheet/modal is open
      document.documentElement.classList.add("apk-modal-open");
      if (which === "editor") {
        refreshEditorTargetTabs();
        colEditorEl.classList.add("sheet-open");
      } else if (which === "log") {
        renderActionLog();
        logModal.classList.add("open");
      } else if (which === "menu") {
        updateSolveModeDesc();
        renderSavedSlotsList();
        menuModal.classList.add("open");
      } else if (which === "diff") {
        renderDiffModal();
        diffModal.classList.add("open");
      } else if (which === "faq") {
        faqModal.classList.add("open");
      } else if (which === "crest") {
        crestModal.classList.add("open");
      }
    }

    function closeAllSheets() {
      clearSheetDragInlineStyles();
      apkUi.activeSheet = null;
      backdrop.classList.remove("open");
      colEditorEl.classList.remove("sheet-open");
      logModal.classList.remove("open");
      menuModal.classList.remove("open");
      diffModal.classList.remove("open");
      faqModal.classList.remove("open");
      crestModal.classList.remove("open");
      // Unlock background scroll when all sheets are closed
      document.documentElement.classList.remove("apk-modal-open");
    }

    // Smooth Swipe-Down-to-Dismiss gesture on ALL slide-up bottom sheets
    function attachSheetSwipeDown(sheetEl, scrollEl, dragHeaderEl) {
      if (!sheetEl) return;
      const handleZone = sheetEl.querySelector("[data-sheet-handle]");
      let active = false;
      let dragging = false;
      let aborted = false;
      let fromHandleOrHdr = false;
      let onlyHandleTap = false;
      let startX = 0;
      let startY = 0;
      let dragStartOffset = 0;
      let lastY = 0;
      let lastT = 0;
      let prevY = 0;
      let prevT = 0;
      let currentDy = 0;

      function isSheetVisible() {
        return sheetEl.classList.contains("sheet-open") || sheetEl.classList.contains("open");
      }

      function onStart(clientX, clientY, target) {
        if (!isSheetVisible()) return false;
        const inHandle = !!(handleZone && handleZone.contains(target));
        const inInteractive = !!(target && target.closest && target.closest("button, input, select, textarea, a, label"));
        if (inInteractive && !inHandle) return false;

        const inHdr = inHandle || !!(dragHeaderEl && dragHeaderEl.contains(target));
        const atScrollTop = !scrollEl || scrollEl.scrollTop <= 2;
        if (!inHdr && !atScrollTop) return false;

        active = true;
        dragging = false;
        aborted = false;
        fromHandleOrHdr = inHdr;
        onlyHandleTap = inHandle;
        startX = clientX;
        startY = clientY;
        dragStartOffset = 0;
        currentDy = 0;
        const now = performance.now();
        lastY = clientY;
        prevY = clientY;
        lastT = now;
        prevT = now;
        return true;
      }

      function onMove(clientX, clientY, rawEv) {
        if (!active || aborted) return;
        const dx = clientX - startX;
        const dy = clientY - startY;
        if (Math.abs(dx) > 7 || Math.abs(dy) > 7) {
          onlyHandleTap = false;
        }

        if (!dragging) {
          if (fromHandleOrHdr) {
            if (dy < -8 || Math.abs(dx) > Math.abs(dy) * 1.35 + 6) {
              aborted = true;
              return;
            }
            if (dy > 3) {
              dragging = true;
              dragStartOffset = 3;
            }
          } else {
            if ((scrollEl && scrollEl.scrollTop > 2) || dy < -5 || Math.abs(dx) > Math.abs(dy) * 1.1 + 5) {
              aborted = true;
              return;
            }
            if (dy > 10) {
              dragging = true;
              dragStartOffset = 10;
            }
          }
          if (dragging) {
            sheetEl.classList.add("is-dragging");
            sheetEl.style.transition = "none";
            backdrop.style.transition = "none";
          }
        }

        if (dragging) {
          if (rawEv && rawEv.cancelable) rawEv.preventDefault();
          const now = performance.now();
          if (now - lastT >= 12) {
            prevY = lastY;
            prevT = lastT;
            lastY = clientY;
            lastT = now;
          }
          currentDy = Math.max(0, dy - dragStartOffset);
          sheetEl.style.transform = `translateY(${Math.round(currentDy)}px)`;
          const fade = Math.max(0.15, 1 - currentDy / 280);
          backdrop.style.opacity = String(Math.round(fade * 100) / 100);
        }
      }

      function onEnd() {
        if (!active) return;
        const wasDragging = dragging;
        const wasHandleTap = onlyHandleTap;
        active = false;
        dragging = false;
        sheetEl.classList.remove("is-dragging");

        if (!wasDragging) {
          if (wasHandleTap) {
            closeAllSheets();
          }
          return;
        }

        const dt = Math.max(1, lastT - prevT);
        const vy = (lastY - prevY) / dt; // px per ms
        const sheetH = sheetEl.offsetHeight || 360;
        const dismissThreshold = Math.min(82, Math.max(54, sheetH * 0.17));

        if (currentDy >= dismissThreshold || (currentDy >= 20 && vy >= 0.40)) {
          sheetEl.style.transition = "transform 0.17s ease-out";
          sheetEl.style.transform = "translateY(108%)";
          backdrop.style.transition = "opacity 0.17s ease-out";
          backdrop.style.opacity = "0";
          sheetAnimCleanupTimer = setTimeout(() => {
            closeAllSheets();
          }, 170);
        } else {
          sheetEl.style.transition = "transform 0.18s cubic-bezier(0.2, 0.8, 0.2, 1)";
          sheetEl.style.transform = "translateY(0px)";
          backdrop.style.transition = "opacity 0.18s ease";
          backdrop.style.opacity = "";
          sheetAnimCleanupTimer = setTimeout(() => {
            clearSheetDragInlineStyles();
          }, 185);
        }
      }

      sheetEl.addEventListener("touchstart", (ev) => {
        if (ev.touches.length !== 1) return;
        const t = ev.touches[0];
        onStart(t.clientX, t.clientY, ev.target);
      }, { passive: true });

      sheetEl.addEventListener("touchmove", (ev) => {
        if (!active || ev.touches.length !== 1) return;
        const t = ev.touches[0];
        onMove(t.clientX, t.clientY, ev);
      }, { passive: false });

      sheetEl.addEventListener("touchend", () => onEnd(), { passive: true });
      sheetEl.addEventListener("touchcancel", () => onEnd(), { passive: true });

      // Pointer/mouse drag support (for desktop WebView / mouse testing)
      let mouseDown = false;
      const hdrTarget = dragHeaderEl || handleZone;
      if (hdrTarget) {
        hdrTarget.addEventListener("mousedown", (ev) => {
          if (ev.button !== 0) return;
          if (onStart(ev.clientX, ev.clientY, ev.target)) {
            mouseDown = true;
          }
        });
        window.addEventListener("mousemove", (ev) => {
          if (!mouseDown) return;
          onMove(ev.clientX, ev.clientY, ev);
        });
        window.addEventListener("mouseup", () => {
          if (!mouseDown) return;
          mouseDown = false;
          onEnd();
        });
      }
    }

    attachSheetSwipeDown(colEditorEl, colEditorEl, editorTopBar);
    attachSheetSwipeDown(logModal, document.getElementById("apkLogModalBody"), logModal.querySelector(".apk-modal-hdr"));
    attachSheetSwipeDown(faqModal, document.getElementById("apkFaqModalBody"), faqModal.querySelector(".apk-modal-hdr"));
    attachSheetSwipeDown(menuModal, document.getElementById("apkMenuModalBody"), menuModal.querySelector(".apk-modal-hdr"));
    attachSheetSwipeDown(diffModal, document.getElementById("apkDiffModalBody"), diffModal.querySelector(".apk-modal-hdr"));
    attachSheetSwipeDown(crestModal, document.getElementById("apkCrestModalBody"), crestModal.querySelector(".apk-modal-hdr"));

    const sudBrandBarEl = document.getElementById("apkSudBrandBar");
    if (sudBrandBarEl) {
      sudBrandBarEl.addEventListener("click", () => {
        if (apkUi.activeSheet === "crest") closeAllSheets();
        else openSheet("crest");
      });
    }
    if (el.plansContainer) {
      el.plansContainer.addEventListener("click", (ev) => {
        if (ev.target && ev.target.closest && ev.target.closest("[data-open-crest]")) {
          openSheet("crest");
        }
      });
    }

    // Expose to Android hardware Back button & NativeBridge!
    window.apkOpenSheet = openSheet;
    window.closeTopSheetIfAny = function () {
      if (apkUi.activeSheet) {
        closeAllSheets();
        return true;
      }
      return false;
    };

    backdrop.addEventListener("click", closeAllSheets);
    document.getElementById("btnApkCloseEditor").addEventListener("click", closeAllSheets);
    document.querySelectorAll("[data-close-sheet]").forEach((b) => b.addEventListener("click", closeAllSheets));

    document.getElementById("btnApkEditor").addEventListener("click", () => {
      if (apkUi.activeSheet === "editor") closeAllSheets();
      else openSheet("editor");
    });
    document.getElementById("btnApkLog").addEventListener("click", () => {
      if (apkUi.activeSheet === "log") closeAllSheets();
      else openSheet("log");
    });
    document.getElementById("btnApkMenu").addEventListener("click", () => {
      if (apkUi.activeSheet === "menu") closeAllSheets();
      else openSheet("menu");
    });
    document.getElementById("btnApkDiff").addEventListener("click", () => {
      if (apkUi.activeSheet === "diff") closeAllSheets();
      else openSheet("diff");
    });
    document.getElementById("btnApkFaq").addEventListener("click", () => {
      if (apkUi.activeSheet === "faq") closeAllSheets();
      else openSheet("faq");
    });

    document.getElementById("btnApkUndoProxy").addEventListener("click", () => el.btnUndo.click());
    document.getElementById("btnApkRedoProxy").addEventListener("click", () => el.btnRedo.click());
    document.getElementById("btnApkResetStageAction").addEventListener("click", () => {
      el.btnResetStage.click();
      closeAllSheets();
    });
    document.getElementById("btnApkClearBoardAction").addEventListener("click", () => {
      el.btnClearBoard.click();
      closeAllSheets();
    });

    document.getElementById("btnApkOverlay").addEventListener("click", () => {
      if (window.AndroidHost && typeof window.AndroidHost.isOverlayActive === "function" && window.AndroidHost.isOverlayActive()) {
        window.AndroidHost.switchToGame();
      } else if (window.AndroidHost && typeof window.AndroidHost.startCaptureOverlay === "function") {
        syncStateToAndroidHost();
        window.AndroidHost.startCaptureOverlay();
      } else if (el.btnStartAndroidOverlay) {
        el.btnStartAndroidOverlay.click();
      }
    });

    // Auto-open Bottom Sheet editor when user taps a Hex Cell or Hand Slot (unless Brush mode is on)
    el.boardSvg.addEventListener("click", (ev) => {
      const g = ev.target && ev.target.closest ? ev.target.closest(".hex-cell") : null;
      if (g && !state.brushEnabled) {
        openSheet("editor");
      }
    });
    el.handGrid.addEventListener("click", (ev) => {
      if (ev.target && ev.target.closest && ev.target.closest(".slot-clear-btn")) return;
      const slot = ev.target && ev.target.closest ? ev.target.closest(".hand-slot") : null;
      if (slot && !state.brushEnabled) {
        openSheet("editor");
      }
    });

    // Auto-close Log sheet if user clicks a checkpoint restore button
    el.actionLogList.addEventListener("click", (ev) => {
      if (ev.target && ev.target.closest && ev.target.closest(".log-restore-btn")) {
        setTimeout(closeAllSheets, 60);
      }
    });

    // Keep compact header badges & Undo/Redo synced after every render
    function syncApkHeaderState() {
      const stg = getStageCfg(state.stageNo);
      const target = stg ? stg.targetScore : 80;
      const goalEl = document.getElementById("apkGoalShort");
      if (goalEl) goalEl.textContent = `/ ${target}`;
      const pct = Math.min(100, Math.round((state.curScore / Math.max(1, target)) * 100));
      const fillEl = document.getElementById("apkMiniProgFill");
      if (fillEl) fillEl.style.width = `${pct}%`;

      const uBtn = document.getElementById("btnApkUndoProxy");
      const rBtn = document.getElementById("btnApkRedoProxy");
      if (uBtn) uBtn.disabled = el.btnUndo.disabled;
      if (rBtn) rBtn.disabled = el.btnRedo.disabled;

      const lBadge = document.getElementById("apkLogBadge");
      if (lBadge) lBadge.textContent = String(state.actionLog.length);

      const ovBtn = document.getElementById("btnApkOverlay");
      if (ovBtn && window.AndroidHost && typeof window.AndroidHost.isOverlayActive === "function") {
        ovBtn.textContent = window.AndroidHost.isOverlayActive() ? "🎮 В игру" : "🚀 Оверлей";
      }

      const rotBtnEl = document.getElementById("btnApkRotStatus");
      if (rotBtnEl) rotBtnEl.textContent = `🔄 ${state.rotationDeg || 0}°`;

      const dBtn = document.getElementById("btnApkDiff");
      const dBadge = document.getElementById("apkDiffBadge");
      const hasDiffs = window.apkPendingDiff && window.apkPendingDiff.hasPending && Array.isArray(window.apkPendingDiff.diffs) && window.apkPendingDiff.diffs.length > 0;
      const hasOcc = window.apkPendingDiff && window.apkPendingDiff.hasPending && Array.isArray(window.apkPendingDiff.occludedCells) && window.apkPendingDiff.occludedCells.length > 0;
      if (dBtn && dBadge) {
        if (hasDiffs || hasOcc) {
          dBtn.classList.add("has-diff");
          dBadge.textContent = hasDiffs ? String(window.apkPendingDiff.diffs.length) : "🔄";
          dBadge.classList.remove("hidden");
          dBadge.classList.add("warn");
        } else {
          dBtn.classList.remove("has-diff");
          dBadge.classList.add("hidden");
        }
      }

      if (apkUi.activeSheet === "editor") {
        refreshEditorTargetTabs();
      }
      refreshApkModeAdviceUi();

      // Highlight pending diff cells (amber) & occluded cells (cyan) directly on the SVG Hex Board!
      if (hasDiffs || hasOcc) {
        const diffMap = {};
        for (const d of (window.apkPendingDiff.diffs || [])) diffMap[d.cellId] = d;
        const occSet = new Set((window.apkPendingDiff.occludedCells || []).map((o) => o.cellId));
        el.boardSvg.querySelectorAll(".hex-cell").forEach((g) => {
          const cid = parseInt(g.getAttribute("data-cell-id"), 10);
          const polys = g.querySelectorAll("polygon");
          if (polys.length >= 2) {
            if (diffMap[cid]) {
              polys[1].setAttribute("stroke", diffMap[cid].selected ? "#facc15" : "#94a3b8");
              polys[1].setAttribute("stroke-width", diffMap[cid].selected ? "4.2" : "2.5");
            } else if (occSet.has(cid)) {
              polys[1].setAttribute("stroke", "#38bdf8");
              polys[1].setAttribute("stroke-width", "3.6");
            }
          }
        });
      }
    }

    const uiObserver = new MutationObserver(() => syncApkHeaderState());
    uiObserver.observe(el.boardSvg, { childList: true });
    uiObserver.observe(el.actionLogList, { childList: true });

    // =========================================================================
    // DETAILED EDITABLE BOARD DIFF SYNC MODAL
    // =========================================================================
    function formatDiffLayersVisual(layers) {
      if (!layers || !layers.length) {
        return `<span style="color:#94a3b8; font-style:italic;">пусто</span>`;
      }
      return layers.map((l, i) => {
        const isTop = i === layers.length - 1;
        return `<span style="display:inline-flex; align-items:center; gap:2px; margin:1px 2px; padding:1px 5px; border-radius:5px; background:rgba(30,41,59,0.9); border:1px solid rgba(255,255,255,0.22); font-weight:800;">${colorChipHtml(l.colour, false)}<span>×${l.count}${isTop && layers.length > 1 ? "▲" : ""}</span></span>`;
      }).join(" ");
    }

    function computeDiffFallbackInJs(dataUri) {
      return new Promise((resolve) => {
        const img = new Image();
        img.onload = () => {
          const stg = getStageCfg(state.stageNo);
          const vo = OfflineEngine.analyzeImageElement(
            img,
            stg,
            state.curScore,
            state.rotationDeg,
            null,
            Object.keys(state.customPalette).length ? state.customPalette : null,
            state.config
          );
          const recMap = {};
          for (const c of vo.cells || []) recMap[c.cellId] = c;
          const scannedWaits = { "1": [], "2": [], "3": [] };
          for (const w of vo.waits || []) scannedWaits[String(w.slotId)] = w.layers || [];
          const diffs = [];
          for (const sc of stg.cells) {
            const cid = sc.cellId;
            const appCell = state.cells[cid];
            const locks = effectiveLocks(appCell, state.curScore);
            const gameRec = recMap[cid];
            if (locks.isLocked && gameRec && gameRec.locked) continue;
            const appL = locks.isLocked ? [] : (appCell.layers || []).map((x) => ({ colour: x.colour, count: x.count }));
            const gameL = (gameRec && !gameRec.locked && gameRec.layers) ? gameRec.layers.map((x) => ({ colour: x.colour, count: x.count })) : [];
            const same = appL.length === gameL.length && appL.every((a, idx) => a.colour === gameL[idx].colour && a.count === gameL[idx].count);
            if (!same) {
              diffs.push({
                cellId: cid,
                selected: true,
                occludedHint: false,
                note: "",
                appLayers: appL,
                gameLayers: gameL,
              });
            }
          }
          resolve({
            hasPending: true,
            stageNo: vo.detectedStageNo || state.stageNo,
            appScore: state.curScore,
            detectedScore: typeof vo.detectedScore === "number" ? vo.detectedScore : -1,
            scannedWaits,
            diffs,
          });
        };
        img.onerror = () => resolve({ hasPending: false, diffs: [], error: "Ошибка чтения картинки" });
        img.src = dataUri;
      });
    }

    let _diffModalRendering = false;
    function renderDiffModal() {
      if (_diffModalRendering) return; // prevent re-entrant re-renders from double-taps
      _diffModalRendering = true;
      try {
      const body = document.getElementById("apkDiffModalBody");
      const ftr = document.getElementById("apkDiffModalFtr");
      const btnConfirm = document.getElementById("btnApkConfirmDiff");
      const pd = window.apkPendingDiff;

      if (!pd || !pd.hasPending) {
        ftr.classList.add("hidden");
        body.innerHTML = `
          <div style="text-align:center; padding:18px 10px;">
            <div style="font-size:32px; margin-bottom:8px;">🔍</div>
            <div style="font-size:14px; font-weight:800; color:#f8fafc; margin-bottom:6px;">Проверка расхождений поля с игрой (6 ракурсов)</div>
            <div style="font-size:12.2px; color:#94a3b8; line-height:1.45; margin-bottom:14px;">
              Если вы случайно поставили стопку не туда в игре:<br/>
              1) Прямо поверх игры нажмите <b>«? Сверить»</b> в оверлее — приложение само определит угол поля (0°..300°), проверит каждую клетку и, если высокая башня заслоняет низ дальней стопки, подскажет, куда повернуть поле и нажать <b>«🔄 Досканировать ракурс»</b>.<br/>
              2) Либо нажмите <b>«📂 Скрин»</b> выше (а после поворота доски — <b>«🔄 +2-й ракурс»</b>) для объединения двух скриншотов со 100% точностью!
            </div>
          </div>
        `;
        return;
      }

      const diffs = pd.diffs || [];
      const turnIcon = (pd.turnIconOnly || "").trim();
      const detTurnBadge = (pd.detectedTurnBadge || "").trim();
      const diagTitle = (pd.causalDiagnosisRu || "").trim()
        || (diffs.length === 0 ? "✅ Поле совпадает с игрой" : `💡 Изменилось клеток: ${diffs.length}`);

      const diagHeaderHtml = `
        <div style="background:${pd.invalidFrame ? "#450a0a" : (diffs.length === 0 ? "#064e3b" : "#1e293b")}; border:1.5px solid ${pd.invalidFrame ? "#f87171" : (diffs.length === 0 ? "#10b981" : "#f59e0b")}; border-radius:10px; padding:8px 10px; margin-bottom:9px; display:flex; align-items:center; justify-content:space-between; gap:8px; flex-wrap:wrap;">
          <div style="font-size:12.5px; font-weight:800; color:${pd.invalidFrame ? "#fecaca" : (diffs.length === 0 ? "#a7f3d0" : "#fde68a")};">
            ${diagTitle}${detTurnBadge ? ` <span style="color:#7dd3fc;">· ${detTurnBadge}</span>` : ""}
          </div>
          ${(!pd.invalidFrame && turnIcon) ? `<button type="button" id="btnApkInlineMergeAngle" style="padding:4px 10px; font-size:12px; font-weight:800; background:#0369a1; border-color:#7dd3fc; color:#fff;">🔄 ${turnIcon}</button>` : ""}
        </div>
        ${pd.occlusionGuidanceRu ? `<div style="background:#451a03; border:1px solid #fbbf24; border-radius:8px; padding:6px 9px; margin-bottom:8px; font-size:11.5px; color:#fef08a;">${pd.occlusionGuidanceRu}</div>` : ""}
      `;

      if (pd.invalidFrame) {
        ftr.classList.add("hidden");
        body.innerHTML = `
          ${diagHeaderHtml}
          <div style="background:#450a0a; border:1.5px solid #f87171; border-radius:11px; padding:12px; text-align:center;">
            <div style="font-size:13px; font-weight:800; color:#fef2f2;">Экран игры не обнаружен — повторите сверку</div>
            <button type="button" id="btnApkDiffOkDone" style="margin-top:8px; padding:6px 14px; font-weight:800;">✕ Закрыть</button>
          </div>
        `;
        document.getElementById("btnApkDiffOkDone").addEventListener("click", () => {
          window.apkPendingDiff = null;
          if (window.AndroidHost && window.AndroidHost.clearPendingDiffs) window.AndroidHost.clearPendingDiffs();
          closeAllSheets();
          renderAll();
        });
        return;
      }

      if (diffs.length === 0) {
        ftr.classList.add("hidden");
        body.innerHTML = `
          ${diagHeaderHtml}
          <div style="background:#064e3b; border:1.5px solid #10b981; border-radius:11px; padding:12px; text-align:center;">
            <div style="font-size:13.5px; font-weight:800; color:#ecfdf5;">Поле решалки в полном соответствии с игрой${pd.detectedScore >= 0 ? ` · Счёт ${pd.detectedScore}` : ""}</div>
            <button type="button" id="btnApkDiffOkDone" class="btn-success" style="margin-top:8px; padding:6px 16px; font-weight:800;">✓ Ок</button>
          </div>
        `;
        const inlineM = document.getElementById("btnApkInlineMergeAngle");
        if (inlineM) inlineM.addEventListener("click", () => document.getElementById("apkDiffMergeInput").click());
        document.getElementById("btnApkDiffOkDone").addEventListener("click", () => {
          window.apkPendingDiff = null;
          if (window.AndroidHost && window.AndroidHost.clearPendingDiffs) window.AndroidHost.clearPendingDiffs();
          closeAllSheets();
          renderAll();
        });
        return;
      }

      ftr.classList.remove("hidden");
      const selCount = diffs.filter((d) => d.selected).length;
      btnConfirm.textContent = `✅ Привести к игре (${selCount})`;
      btnConfirm.disabled = selCount === 0 && !(apkUi.updateScoreAndHandOnDiff && pd.detectedScore >= 0 && pd.detectedScore !== state.curScore);

      let html = `
        ${diagHeaderHtml}
        <div style="background:#1e293b; border:1px solid #475569; border-radius:9px; padding:7px 10px; margin-bottom:9px; font-size:12px;">
          <div style="display:flex; justify-content:space-between; align-items:center; gap:6px; flex-wrap:wrap;">
            <span>Клеток к исправлению: <b style="color:#fde047;">${diffs.length}</b></span>
            <span style="display:flex; gap:5px;">
              <button type="button" id="btnDiffSelAll" style="padding:2px 7px; font-size:11px;">Все</button>
              <button type="button" id="btnDiffSelNone" style="padding:2px 7px; font-size:11px;">Снять</button>
            </span>
          </div>
          ${pd.detectedScore >= 0 ? `
            <label style="display:flex; align-items:center; gap:6px; margin-top:6px; padding-top:6px; border-top:1px solid #334155; cursor:pointer; color:#bae6fd; font-weight:700;">
              <input type="checkbox" id="chkDiffSyncScoreHand" ${apkUi.updateScoreAndHandOnDiff ? "checked" : ""} />
              <span>Синхронизировать счёт (${state.curScore} → <b>${pd.detectedScore}</b>) и руку</span>
            </label>
          ` : ""}
        </div>
      `;

      diffs.forEach((d, idx) => {
        const isEditing = apkUi.editingDiffIdx === idx;
        html += `
          <div class="apk-diff-card${d.selected ? " selected" : ""}" data-diff-idx="${idx}">
            <div class="apk-diff-card-top">
              <label class="apk-diff-chk-lbl">
                <input type="checkbox" class="apk-diff-row-chk" data-idx="${idx}" ${d.selected ? "checked" : ""} />
                <span>Клетка #${d.cellId}</span>
              </label>
              <button type="button" class="apk-diff-tweak-btn" data-idx="${idx}" style="padding:2px 8px; font-size:11px;">
                ${isEditing ? "▲ Скрыть ред." : "✏️ Подправить"}
              </button>
            </div>
            <div class="apk-diff-compare">
              <div>
                <div class="apk-diff-side-lbl">Сейчас в прилож.:</div>
                <div>${formatDiffLayersVisual(d.appLayers)}</div>
              </div>
              <div style="font-size:15px; font-weight:900; color:#fbbf24;">→</div>
              <div>
                <div class="apk-diff-side-lbl">Решение (игра):</div>
                <div>${formatDiffLayersVisual(d.gameLayers)}</div>
              </div>
            </div>
            ${isEditing ? buildInlineDiffEditorHtml(d, idx) : ""}
          </div>
        `;
      });

      body.innerHTML = html;
      const inlineM = document.getElementById("btnApkInlineMergeAngle");
      if (inlineM) inlineM.addEventListener("click", () => document.getElementById("apkDiffMergeInput").click());

      const chkSH = document.getElementById("chkDiffSyncScoreHand");
      if (chkSH) {
        chkSH.addEventListener("change", () => {
          apkUi.updateScoreAndHandOnDiff = chkSH.checked;
          renderDiffModal();
        });
      }
      document.getElementById("btnDiffSelAll").addEventListener("click", () => {
        diffs.forEach((d) => { d.selected = true; });
        renderDiffModal();
        syncApkHeaderState();
      });
      document.getElementById("btnDiffSelNone").addEventListener("click", () => {
        diffs.forEach((d) => { d.selected = false; });
        renderDiffModal();
        syncApkHeaderState();
      });
      body.querySelectorAll(".apk-diff-row-chk").forEach((chk) => {
        chk.addEventListener("change", () => {
          const i = parseInt(chk.getAttribute("data-idx"), 10);
          diffs[i].selected = chk.checked;
          renderDiffModal();
          syncApkHeaderState();
        });
      });
      body.querySelectorAll(".apk-diff-tweak-btn").forEach((b) => {
        b.addEventListener("click", () => {
          const i = parseInt(b.getAttribute("data-idx"), 10);
          apkUi.editingDiffIdx = apkUi.editingDiffIdx === i ? -1 : i;
          renderDiffModal();
        });
      });
      wireInlineDiffEditorEvents(body, diffs);
      } finally {
        _diffModalRendering = false;
      }
    }

    function buildInlineDiffEditorHtml(d, idx) {
      const layerRows = (d.gameLayers || []).map((l, lIdx) => {
        const isTop = lIdx === d.gameLayers.length - 1;
        const isBot = lIdx === 0 && d.gameLayers.length > 1;
        return `
          <div class="apk-diff-edit-layer">
            <span>${colorChipHtml(l.colour, false)} <b>${getEmblemShortName(l.colour)}</b> ${isTop ? "▲ (верх)" : (isBot ? "(низ)" : "")}</span>
            <span style="display:inline-flex; align-items:center; gap:4px;">
              <button type="button" data-dact="dec" data-idx="${idx}" data-l="${lIdx}" style="padding:1px 7px;">−</button>
              <b style="min-width:20px; text-align:center;">${l.count}</b>
              <button type="button" data-dact="inc" data-idx="${idx}" data-l="${lIdx}" style="padding:1px 7px;">+</button>
              <button type="button" data-dact="del" data-idx="${idx}" data-l="${lIdx}" class="btn-danger-soft" style="padding:1px 6px;">✕</button>
            </span>
          </div>
        `;
      }).reverse().join("");
      const addBtns = [1, 2, 3, 4, 5, 6, 7, 8].map((cid) =>
        `<button type="button" data-dact="add" data-idx="${idx}" data-col="${cid}" style="padding:3px 4px; font-size:11px;">${colorChipHtml(cid, false)}</button>`
      ).join("");
      return `
        <div class="apk-diff-inline-edit">
          <div style="font-size:11px; color:#94a3b8;">Ручная правка новой стопки для #${d.cellId} (+1 сверху):</div>
          ${layerRows || `<div style="font-size:11px; color:#64748b;">Стопка пуста — нажмите значок ниже, чтобы добавить слой сверху:</div>`}
          <div style="display:grid; grid-template-columns:repeat(8, 1fr); gap:3px; margin-top:2px;">${addBtns}</div>
        </div>
      `;
    }

    function wireInlineDiffEditorEvents(container, diffs) {
      container.querySelectorAll("[data-dact]").forEach((btn) => {
        btn.addEventListener("click", () => {
          const act = btn.getAttribute("data-dact");
          const idx = parseInt(btn.getAttribute("data-idx"), 10);
          const d = diffs[idx];
          if (!d) return;
          d.selected = true;
          if (act === "inc") {
            const li = parseInt(btn.getAttribute("data-l"), 10);
            if (d.gameLayers[li]) d.gameLayers[li].count = Math.min(9, d.gameLayers[li].count + 1);
          } else if (act === "dec") {
            const li = parseInt(btn.getAttribute("data-l"), 10);
            if (d.gameLayers[li]) {
              d.gameLayers[li].count -= 1;
              if (d.gameLayers[li].count <= 0) d.gameLayers.splice(li, 1);
            }
          } else if (act === "del") {
            const li = parseInt(btn.getAttribute("data-l"), 10);
            d.gameLayers.splice(li, 1);
          } else if (act === "add") {
            const col = parseInt(btn.getAttribute("data-col"), 10);
            d.gameLayers = normalizeLayers([...(d.gameLayers || []), { colour: col, count: 1 }]);
          }
          d.gameLayers = normalizeLayers(d.gameLayers || []);
          renderDiffModal();
          syncApkHeaderState();
        });
      });
    }

    document.getElementById("btnApkConfirmDiff").addEventListener("click", () => {
      const pd = window.apkPendingDiff;
      if (!pd || !pd.hasPending) return;
      pushUndoSnapshot();

      const diffScreenId = pd.lastScreenId || null;
      const subHtml = [];
      const subPlain = [];
      let changedCount = 0;

      for (const d of pd.diffs || []) {
        if (!d.selected) continue;
        const cell = state.cells[d.cellId];
        if (!cell || cell.exist !== 1) continue;
        const beforeHtml = formatLayersHtml(cell.layers);
        const beforePlain = formatLayersPlain(cell.layers);
        cell.layers = normalizeLayers((d.gameLayers || []).map((l) => ({ colour: l.colour, count: l.count })));
        const afterHtml = formatLayersHtml(cell.layers);
        const afterPlain = formatLayersPlain(cell.layers);
        subHtml.push(`Клетка <b>#${d.cellId}</b>: ${beforeHtml} → ${afterHtml}`);
        subPlain.push(`Клетка #${d.cellId}: ${beforePlain} -> ${afterPlain}`);
        changedCount++;
      }

      if (apkUi.updateScoreAndHandOnDiff) {
        if (pd.detectedScore >= 0) {
          state.curScore = pd.detectedScore;
          for (let cid = 1; cid <= 42; cid++) {
            const c = state.cells[cid];
            if (c && c.exist === 1) {
              if (c.lockScore > 0 && state.curScore >= c.lockScore) c.lockScore = 0;
              if (c.lockCardScore > 0 && state.curScore >= c.lockCardScore) c.lockCardScore = 0;
            }
          }
        }
        if (pd.scannedWaits) {
          const anyWait = [1, 2, 3].some((s) => Array.isArray(pd.scannedWaits[String(s)]) && pd.scannedWaits[String(s)].length > 0);
          if (anyWait) {
            for (const s of [1, 2, 3]) {
              state.waits[s] = normalizeLayers((pd.scannedWaits[String(s)] || []).map((l) => ({ colour: l.colour, count: l.count })));
            }
          }
        }
      }

      window.apkPendingDiff = null;
      apkUi.editingDiffIdx = -1;
      if (window.AndroidHost && window.AndroidHost.clearPendingDiffs) {
        window.AndroidHost.clearPendingDiffs();
      }

      closeAllSheets();
      renderAll();
      appendLog({
        cat: "cv",
        tag: "СВЕРКА",
        html: `🔍 Подтверждена замена столбцов с игрой: обновлено клеток <b>${changedCount}</b> (Этап ${state.stageNo}, Счёт ${state.curScore})`,
        plain: `Подтверждена замена столбцов с игрой: обновлено клеток ${changedCount} (Этап ${state.stageNo}, Счёт ${state.curScore})`,
        subLinesHtml: subHtml,
        subLinesPlain: subPlain,
        screenId: diffScreenId,
      });
      runSolver();
    });

    document.getElementById("btnApkCancelDiff").addEventListener("click", () => {
      window.apkPendingDiff = null;
      apkUi.editingDiffIdx = -1;
      if (window.AndroidHost && window.AndroidHost.clearPendingDiffs) {
        window.AndroidHost.clearPendingDiffs();
      }
      closeAllSheets();
      renderAll();
    });

    // Screenshot picker & 2nd-angle merge picker inside Board Diff Modal
    async function runDiffScreenshotFile(fileObj, mergeSecondAngle) {
      if (!fileObj) return;
      const reader = new FileReader();
      reader.onload = async () => {
        const dataUri = String(reader.result || "");
        const body = document.getElementById("apkDiffModalBody");
        const pickBtn = document.getElementById("btnApkPickDiffShot");
        const mergeBtn = document.getElementById("btnApkMergeDiffShot");
        // Show progress: spin buttons + status text
        if (pickBtn) { pickBtn.classList.add("apk-scanning"); pickBtn.textContent = "⏳ Сканирую..."; }
        if (mergeBtn) { mergeBtn.classList.add("apk-scanning"); }
        body.innerHTML = `<div style="padding:18px; text-align:center; color:#bae6fd; font-weight:700;">⏳ ${mergeSecondAngle ? "Объединяю ракурс и раскрываю закрытые стопки..." : "Сверяю каждый столбец со скриншотом..."}</div>`;
        let resObj = null;
        try {
          if (window.AndroidHost) {
            if (!mergeSecondAngle) syncStateToAndroidHost();
            const fnName = mergeSecondAngle && typeof window.AndroidHost.mergeScreenshotDiffJson === "function"
              ? "mergeScreenshotDiffJson"
              : "scanScreenshotDiffJson";
            if (typeof window.AndroidHost[fnName] === "function") {
              resObj = JSON.parse(window.AndroidHost[fnName](dataUri) || "{}");
            }
          }
        } catch (err) {
          console.warn("AndroidHost diff scan err", err);
        } finally {
          // Restore button state
          if (pickBtn) { pickBtn.classList.remove("apk-scanning"); pickBtn.textContent = "📂 Скрин"; }
          if (mergeBtn) { mergeBtn.classList.remove("apk-scanning"); }
        }
        if (!resObj || !resObj.hasPending) {
          resObj = await computeDiffFallbackInJs(dataUri);
        }
        window.apkPendingDiff = resObj;
        if (resObj && resObj.hasPending) {
          const diffSubsPlain = [];
          for (const d of resObj.diffs || []) {
            diffSubsPlain.push(`• Клетка #${d.cellId}: в прилож. [${formatLayersPlain(d.appLayers)}] ↔ в игре [${formatLayersPlain(d.gameLayers)}]`);
          }
          appendLog({
            cat: "cv",
            tag: "СВЕРКА",
            html: `🔍 Сверка по скриншоту (Этап ${state.stageNo}, отличий: <b>${(resObj.diffs || []).length}</b>)`,
            plain: `🔍 Сверка по скриншоту (Этап ${state.stageNo}, отличий: ${(resObj.diffs || []).length})`,
            subLinesHtml: diffSubsPlain,
            subLinesPlain: diffSubsPlain,
            withCheckpoint: false,
            screenId: resObj.lastScreenId || null,
          });
        }
        apkUi.editingDiffIdx = -1;
        renderDiffModal();
        renderBoardSvg();
        syncApkHeaderState();
      };
      reader.readAsDataURL(fileObj);
    }

    const diffFileInp = document.getElementById("apkDiffFileInput");
    const diffMergeInp = document.getElementById("apkDiffMergeInput");
    document.getElementById("btnApkPickDiffShot").addEventListener("click", () => diffFileInp.click());
    document.getElementById("btnApkMergeDiffShot").addEventListener("click", () => diffMergeInp.click());
    diffFileInp.addEventListener("change", () => {
      runDiffScreenshotFile(diffFileInp.files && diffFileInp.files[0], false);
      diffFileInp.value = "";
    });
    diffMergeInp.addEventListener("change", () => {
      runDiffScreenshotFile(diffMergeInp.files && diffMergeInp.files[0], true);
      diffMergeInp.value = "";
    });

    syncApkHeaderState();
"""


def main() -> None:
    html = SRC.read_text(encoding="utf-8")
    # Use WebP file instead of inline base64 — saves ~30 KB and loads faster
    crest_uri = "file:///android_asset/sud_crest.webp"

    # 0. Remove legacy OfflineEngine JS solver (2110 lines) — fully replaced by native SolverEngine.java
    #    In the APK, AndroidHost.solveHandJson is always available, so the JS fallback is dead code.
    import re
    _oe_start = html.find("const OfflineEngine = (() => {")
    if _oe_start == -1:
        raise RuntimeError("Expected OfflineEngine block in source HTML")
    _oe_end_marker = "})();"
    _oe_end = html.find(_oe_end_marker, _oe_start)
    if _oe_end == -1:
        raise RuntimeError("Expected OfflineEngine IIFE close in source HTML")
    _oe_end += len(_oe_end_marker)
    html = html[:_oe_start] + html[_oe_end:]
    # Replace OfflineEngine references in computeDiffFallbackInJs with a safe error result
    # (native bridge is always available in APK, so this fallback should never be hit)
    html = html.replace(
        "      const vo = OfflineEngine.analyzeImageElement(",
        "      if (typeof OfflineEngine === \"undefined\") { resolve({ hasPending: false, diffs: [], error: \"OfflineEngine removed — use native bridge\" }); return; }\n      const vo = OfflineEngine.analyzeImageElement(",
        1,
    )
    # Remove the OfflineEngine.generateDemoDataUri reference (demo button is hidden in APK anyway)
    html = html.replace(
        "      const uri = OfflineEngine.generateDemoDataUri(stg, state.config);",
        "      const uri = state.cvImageDataUri || '';",
        1,
    )

    # 1. Enforce topK: 1 strictly inside APK solver calls + default stack editor count=1 & append-on-top
    if "topK: 5," not in html:
        raise RuntimeError("Expected 'topK: 5,' in source HTML")
        # --- Bug 1.0 fix: unguarded OfflineEngine call sites → native bridge / friendly error ---
    # 🎯 Расчёт: route through native AndroidHost.solveHandJson (AppState.solveWebJson).
    _oh_old = "      const data = OfflineEngine.solveHand({"
    assert html.count(_oh_old) == 1, f"solveHand site count={html.count(_oh_old)}"
    html = html.replace(_oh_old,
        "      const data = (typeof window.AndroidHost !== \"undefined\" && typeof window.AndroidHost.solveHandJson === \"function\")\n"
        "        ? JSON.parse(window.AndroidHost.solveHandJson(JSON.stringify({")

    _oh2_old = "      waits: waitsPayload,\n      }, state.config);"
    assert html.count(_oh2_old) == 1, f"solveHand tail count={html.count(_oh2_old)}"
    html = html.replace(_oh2_old,
        "      waits: waitsPayload,\n      })))\n"
        "        : (function () { throw new Error(\"Нативный решатель недоступен (нет AndroidHost)\"); })();")

    # 📂 Скрин: нет нативного полного CV-анализа — вежливая ошибка вместо ReferenceError.
    _ai_old = "      const data = OfflineEngine.analyzeImageElement("
    assert html.count(_ai_old) == 1, f"analyzeImageElement site count={html.count(_ai_old)}"
    html = html.replace(_ai_old,
        "      if (typeof OfflineEngine === \"undefined\") {\n"
        "        throw new Error(\"Распознавание снимка выключено в АПК — делай скан кнопкой в плавающем окне\");\n"
        "      }\n"
        "      const data = OfflineEngine.analyzeImageElement(")

    html = html.replace("topK: 5,", "topK: 1,", 1)

    if "brushCount: 4," not in html or 'brushAction: "replace",' not in html:
        raise RuntimeError("Expected brushCount/brushAction defaults in source HTML")
    html = html.replace("brushCount: 4,", "brushCount: 1,", 1)
    html = html.replace('brushAction: "replace",', 'brushAction: "append",', 1)
    html = html.replace(
        '<option value="append">Добавить слой сверху</option>',
        '<option value="append" selected>Добавить слой сверху</option>',
        1,
    )
    html = html.replace(
        '<title>Решалка «Двойная радость» — Мобильная + Плавающее окно (Android)</title>',
        '<title>"SUD" Suicide Squad — Двойная радость!</title>',
        1,
    )

    # 1b. Replace empty-hand placeholder with the [SUD] Suicide Squad Crest Showcase Card
    old_empty_plans = """    if (!state.plans.length) {
      el.plansContainer.innerHTML = `<div class="hint-text" style="padding:12px; text-align:center; background:var(--bg-input); border-radius:9px; border:1px solid var(--border);">Задайте хотя бы одну стопку в руке (Слоты #1–#3), чтобы решалка нашла оптимальные ходы.</div>`;
      return;
    }"""
    new_empty_plans = """    if (!state.plans.length) {
      el.plansContainer.innerHTML = `
        <div class="apk-crest-showcase-card" data-open-crest="1" style="cursor:pointer;" title="Нажмите, чтобы увеличить герб [SUD] Suicide Squad">
          <img src="${window.APK_SUD_CREST_URI}" alt="[SUD] Suicide Squad — Двойная радость!" />
          <div class="apk-crest-showcase-hint">Задайте стопки в руке или нажмите 🚀 Оверлей → ✋ Рука (коснитесь герба для увеличения)</div>
        </div>
      `;
      return;
    }"""
    if old_empty_plans not in html:
        raise RuntimeError("Expected empty plans placeholder in source HTML")
    html = html.replace(old_empty_plans, new_empty_plans, 1)

    # 2. Delegate APK WebView solver to Java Grandmaster + Score-Aware Stratified Rollout engine
    #    (skipped when OfflineEngine block was removed — native bridge is called via runtime JS instead)
    if SOLVE_HAND_BRIDGE_OLD in html:
        html = html.replace(SOLVE_HAND_BRIDGE_OLD, SOLVE_HAND_BRIDGE_NEW, 1)

    old_js_exp = "const expectimaxScore = pSingleMerge * 135 + pBurstNext * 150 + hubScore + bridgeBonus + breathingHighBonus - walledPenalty;"
    new_js_exp = """let emptyPairCnt = 0;
    for (const ecid of emptyIds) {
      for (const nbId of (adjTable[String(ecid)] || [])) {
        if (nbId > ecid && emptySet.has(nbId)) emptyPairCnt++;
      }
    }
    const adjEmptyBonus = Math.min(6, emptyPairCnt) * 14.5;
    const expectimaxScore = pSingleMerge * 135 + pBurstNext * 150 + hubScore + bridgeBonus + adjEmptyBonus + breathingHighBonus - walledPenalty;"""
    if old_js_exp in html:
        html = html.replace(old_js_exp, new_js_exp, 1)

    # 3. Anti-misclick slot headers in Hand section
    old_slot_hdr = 'header.innerHTML = `<span>Слот #${slotId}</span>`;'
    new_slot_hdr = 'header.innerHTML = `<span class="apk-slot-pill s${slotId}">${window.APK_SLOT_LABELS[slotId] || ("Слот #" + slotId)}</span>`;'
    if old_slot_hdr not in html:
        raise RuntimeError("Expected hand slot header in source HTML")
    html = html.replace(old_slot_hdr, new_slot_hdr, 1)

    # 4. Inject Red Reroll Booster Alert Banner in renderPlans()
    old_summary_block = """      const summary = document.createElement("div");
      summary.className = "plan-summary";
      summary.textContent = plan.summary;
      card.appendChild(summary);"""
    new_summary_block = """      if (plan.recommendReroll && plan.rerollReason && plan.rerollTipOk !== false) {
        const rerollBox = document.createElement("div");
        rerollBox.className = "apk-reroll-alert" + ((plan.deadlockStopAll || plan.finalEmptyCells === 0) ? "" : " warn");
        rerollBox.textContent = plan.rerollReason;
        card.appendChild(rerollBox);
      }
      if (plan.hammerCellId && plan.hammerReasonRu) {
        const hammerBox = document.createElement("div");
        hammerBox.style.cssText = "margin:5px 0 6px;padding:7px 9px;border-radius:8px;background:rgba(59,7,100,0.85);border:1.2px solid #c084fc;color:#f3e8ff;font-size:12px;font-weight:700;line-height:1.35;";
        hammerBox.textContent = plan.hammerReasonRu;
        card.appendChild(hammerBox);
      }
      const summary = document.createElement("div");
      summary.className = "plan-summary";
      summary.textContent = plan.summary;
      card.appendChild(summary);"""
    if old_summary_block not in html:
        raise RuntimeError("Expected plan summary block in source HTML")
    html = html.replace(old_summary_block, new_summary_block, 1)

    # 5. Anti-misclick step badges in renderPlans() & Apply Step 1 button (safe when plan.steps is empty!)
    old_step_lbl = '<b>Ход ${st.stepNum}:</b> Слот #${st.slotId} ${colorChipHtml(topColId, true)} → <b>Клетка #${st.targetCellId}</b>${unlockNoteHtml}'
    new_step_lbl = '<b>ШАГ ${st.stepNum}:</b> <span class="apk-slot-pill s${st.slotId}">${window.APK_SLOT_LABELS[st.slotId] || ("Слот #" + st.slotId)}</span> ${colorChipHtml(topColId, true)} → <b>в #${st.targetCellId}</b>${unlockNoteHtml}'
    if old_step_lbl not in html:
        raise RuntimeError("Expected step label in source HTML")
    html = html.replace(old_step_lbl, new_step_lbl, 1)

    old_apply1 = """      const btnApplyOne = document.createElement("button");
      btnApplyOne.textContent = `▶ Выполнить Ход 1 (Слот #${plan.steps[0].slotId} → #${plan.steps[0].targetCellId})`;
      btnApplyOne.addEventListener("click", () => applyPlanSteps(plan, 1));
      actionsRow.appendChild(btnApplyOne);"""
    new_apply1 = """      if (plan.steps && plan.steps.length > 0) {
        const btnApplyOne = document.createElement("button");
        btnApplyOne.textContent = `▶ Выполнить ШАГ 1 (${window.APK_SLOT_LABELS[plan.steps[0].slotId] || ("Слот #" + plan.steps[0].slotId)} → #${plan.steps[0].targetCellId})` + (plan.partialBeforeReroll && plan.steps.length === 1 ? " · ЗАТЕМ 🔄!" : "");
        btnApplyOne.addEventListener("click", () => applyPlanSteps(plan, 1));
        actionsRow.appendChild(btnApplyOne);
      } else {
        const stopBadge = document.createElement("div");
        stopBadge.style.cssText = "width:100%;padding:8px 10px;border-radius:8px;background:#450a0a;border:1.4px solid #f87171;color:#fecaca;font-size:12.2px;font-weight:800;text-align:center;";
        stopBadge.textContent = "🛑 СТОП! НЕ СТАВЬТЕ ПАЧКИ" + (plan.rerollTipOk !== false ? " — нажмите в игре 🔄 Перемешать" : "") + (plan.hammerCellId ? (" или ударьте 🔨 #" + plan.hammerCellId) : "");
        actionsRow.appendChild(stopBadge);
      }"""
    if old_apply1 not in html:
        raise RuntimeError("Expected btnApplyOne label in source HTML")
    html = html.replace(old_apply1, new_apply1, 1)

    # 6. Upgrade appendLog, computeLogHandStats, btnCopyLog, and Native Android Downloads support
    old_append_log = """  function appendLog({ cat = "edit", tag = "ИНФО", html = "", plain = "", subLinesHtml = [], subLinesPlain = [], withCheckpoint = true }) {
    const ts = formatNowParts();
    state.logSeq += 1;
    const entry = {
      seq: state.logSeq,
      time: ts.time,
      fullTs: ts.full,
      cat,
      tag,
      html,
      plain,
      subLinesHtml,
      subLinesPlain,
      checkpoint: withCheckpoint
        ? {
            stageNo: state.stageNo,
            curScore: state.curScore,
            cells: deepCopy(state.cells),
            waits: deepCopy(state.waits),
          }
        : null,
    };
    state.actionLog.push(entry);
    if (state.actionLog.length > 150) {
      state.actionLog = state.actionLog.slice(-150);
    }
    // Trim older checkpoints in memory beyond last 35 entries
    if (state.actionLog.length > 35) {
      const oldIdx = state.actionLog.length - 36;
      if (state.actionLog[oldIdx]) state.actionLog[oldIdx].checkpoint = null;
    }

    const diskLines = [`[${ts.full}] [#${entry.seq}] [${tag}] ${plain}`];
    for (const sl of subLinesPlain || []) {
      diskLines.push(`    ↳ ${sl}`);
    }
    pushRemoteDiskLog(diskLines);

    renderActionLog();
    saveAutoProgress();
  }"""
    new_append_log = r"""  function convertNativeSnapshotToWebCheckpoint(snap) {
    if (!snap || !snap.cells || !snap.waits) return null;
    const stgNo = Number(snap.stageNo) || state.stageNo || 1;
    const stg = getStageCfg(stgNo);
    const cellsMap = {};
    for (let cid = 1; cid <= 42; cid++) {
      cellsMap[cid] = { cellId: cid, exist: 0, unlockCellScore: 0, unlockCardScore: 0, layers: [] };
    }
    if (stg && Array.isArray(stg.cells)) {
      for (const sc of stg.cells) {
        const savedCell = snap.cells[sc.cellId] || snap.cells[String(sc.cellId)];
        cellsMap[sc.cellId] = {
          cellId: sc.cellId,
          exist: 1,
          unlockCellScore: (savedCell && typeof savedCell.lockScore === "number") ? savedCell.lockScore : (sc.unlockCellScore || 0),
          unlockCardScore: (savedCell && typeof savedCell.lockCardScore === "number") ? savedCell.lockCardScore : (sc.unlockCardScore || 0),
          layers: normalizeLayers((savedCell && savedCell.layers) || []),
        };
      }
    }
    return {
      stageNo: stgNo,
      curScore: Number(snap.curScore) || 0,
      cells: cellsMap,
      waits: {
        1: normalizeLayers((snap.waits && (snap.waits[1] || snap.waits["1"])) || []),
        2: normalizeLayers((snap.waits && (snap.waits[2] || snap.waits["2"])) || []),
        3: normalizeLayers((snap.waits && (snap.waits[3] || snap.waits["3"])) || []),
      },
    };
  }

  function appendLog({
    cat = "edit",
    tag = "ИНФО",
    html = "",
    plain = "",
    subLinesHtml = [],
    subLinesPlain = [],
    withCheckpoint = true,
    time = null,
    fullTs = null,
    customCheckpoint = null,
    skipRenderSave = false,
    screenId = null,
    uiScreenId = null,
  }) {
    const ts = formatNowParts();
    const effTime = time || ts.time;
    const effFullTs = fullTs || ts.full;
    const cpObj = customCheckpoint || (withCheckpoint
      ? {
          stageNo: state.stageNo,
          curScore: state.curScore,
          cells: deepCopy(state.cells),
          waits: deepCopy(state.waits),
        }
      : null);

    // Coalesce consecutive manual +1/-1 stack edits on the exact same Slot or Cell
    // so building a multi-layer stack creates 1 clean log record instead of 8 partial lines
    if (cat === "edit" && (tag === "РУКА" || tag === "ПОЛЕ") && state.actionLog.length > 0) {
      const prev = state.actionLog[state.actionLog.length - 1];
      const targetMatch = String(plain || "").match(/^(Слот #\d+|Клетка #\d+)/);
      const prevMatch = prev && prev.cat === "edit" && prev.tag === tag
        ? String(prev.plain || "").match(/^(Слот #\d+|Клетка #\d+)/)
        : null;
      if (targetMatch && prevMatch && targetMatch[1] === prevMatch[1]) {
        prev.time = effTime;
        prev.fullTs = effFullTs;
        prev.html = html;
        prev.plain = plain;
        prev.subLinesHtml = subLinesHtml;
        prev.subLinesPlain = subLinesPlain;
        if (screenId) prev.screenId = screenId;
        if (uiScreenId) prev.uiScreenId = uiScreenId;
        if (cpObj) prev.checkpoint = cpObj;
        if (!skipRenderSave) {
          renderActionLog();
          saveAutoProgress();
        }
        return;
      }
    }

    state.logSeq += 1;
    const entry = {
      seq: state.logSeq,
      time: effTime,
      fullTs: effFullTs,
      cat,
      tag,
      html,
      plain,
      subLinesHtml,
      subLinesPlain,
      screenId: screenId || null,
      uiScreenId: uiScreenId || null,
      checkpoint: cpObj,
    };
    state.actionLog.push(entry);
    if (state.actionLog.length > 800) {
      state.actionLog = state.actionLog.slice(-800);
    }
    // Keep last 60 rollback checkpoints in memory
    if (state.actionLog.length > 60) {
      const oldIdx = state.actionLog.length - 61;
      if (state.actionLog[oldIdx]) state.actionLog[oldIdx].checkpoint = null;
    }

    const diskLines = [`[${effFullTs}] [#${entry.seq}] [${tag}] ${plain}`];
    if (entry.uiScreenId) {
      diskLines.push(`    ↳ 🪟 СКРИН С ОКОШКОМ #${entry.seq}: ${entry.uiScreenId}`);
    }
    if (entry.screenId) {
      diskLines.push(`    ↳ 📸 СКРИН ЧИСТЫЙ #${entry.seq}: ${entry.screenId}`);
    }
    for (const sl of subLinesPlain || []) {
      diskLines.push(`    ↳ ${sl}`);
    }
    pushRemoteDiskLog(diskLines);

    if (!skipRenderSave) {
      renderActionLog();
      saveAutoProgress();
    }
  }"""
    if old_append_log not in html:
        raise RuntimeError("Expected appendLog function in source HTML")
    html = html.replace(old_append_log, new_append_log, 1)

    # Keep up to 400 log items in localStorage (with lightweight screenId strings, checkpoints on latest 30)
    old_autosave_slice = "const trimmedLog = state.actionLog.slice(-100).map((entry, idx, arr) => {"
    new_autosave_slice = "const trimmedLog = state.actionLog.slice(-400).map((entry, idx, arr) => {"
    if old_autosave_slice not in html:
        raise RuntimeError("Expected saveAutoProgress slice(-100) in source HTML")
    html = html.replace(old_autosave_slice, new_autosave_slice, 1)

    # Render 📸 Скрин ▼ preview button inside each log card that has screenId + include screen note in plain log
    old_render_log_tail = """      if (item.subLinesHtml && item.subLinesHtml.length > 0) {
        const sub = document.createElement("div");
        sub.className = "log-entry-sub";
        sub.innerHTML = item.subLinesHtml.map((s) => `<div style="display:flex;align-items:center;gap:4px;flex-wrap:wrap;">${s}</div>`).join("");
        div.appendChild(sub);
      }

      el.actionLogList.appendChild(div);
    }
  }

  function buildFullPlainLogText() {
    const lines = [
      `=== ЛОГ РЕШАЛКИ «ДВОЙНАЯ РАДОСТЬ» (Этап ${state.stageNo}, Текущий счёт: ${state.curScore}) ===`,
    ];
    for (const item of state.actionLog) {
      lines.push(`[${item.fullTs || item.time}] [#${item.seq}] [${item.tag}] ${item.plain}`);
      for (const sl of item.subLinesPlain || []) {
        lines.push(`    -> ${sl}`);
      }
    }
    return lines.join("\\n");
  }"""
    new_render_log_tail = """      if (item.subLinesHtml && item.subLinesHtml.length > 0) {
        const sub = document.createElement("div");
        sub.className = "log-entry-sub";
        sub.innerHTML = item.subLinesHtml.map((s) => `<div style="display:flex;align-items:center;gap:4px;flex-wrap:wrap;">${s}</div>`).join("");
        div.appendChild(sub);
      }

      if ((item.uiScreenId || item.screenId) && window.AndroidHost && typeof window.AndroidHost.getLogScreenDataUri === "function") {
        const scrWrap = document.createElement("div");
        scrWrap.style.cssText = "margin-top:5px;display:flex;gap:6px;flex-wrap:wrap;align-items:center;";
        const imgHolder = document.createElement("div");
        imgHolder.style.cssText = "display:none;margin-top:6px;width:100%;";
        function addScrBtn(sidVal, labelBase, borderCol) {
          if (!sidVal) return;
          const btnScr = document.createElement("button");
          btnScr.type = "button";
          btnScr.className = "log-restore-btn";
          btnScr.style.cssText = `background:#0f2a44;border-color:${borderCol};color:#bae6fd;font-weight:700;`;
          btnScr.textContent = `${labelBase} ▼`;
          btnScr.addEventListener("click", () => {
            if (imgHolder.style.display === "none" || imgHolder.dataset.activeSid !== sidVal) {
              const uri = window.AndroidHost.getLogScreenDataUri(sidVal);
              if (uri) {
                imgHolder.innerHTML = `<img src="${uri}" style="max-width:100%;max-height:360px;border-radius:8px;border:1px solid ${borderCol};display:block;" alt="${labelBase}" />`;
              } else {
                imgHolder.innerHTML = `<span style="font-size:11px;color:#94a3b8;">Скриншот очищен из кэша</span>`;
              }
              imgHolder.dataset.activeSid = sidVal;
              imgHolder.style.display = "block";
            } else {
              imgHolder.style.display = "none";
              imgHolder.dataset.activeSid = "";
            }
          });
          scrWrap.appendChild(btnScr);
        }
        addScrBtn(item.uiScreenId, `🪟 С окошком #${item.seq}`, "#a78bfa");
        addScrBtn(item.screenId, `📸 Чистый #${item.seq}`, "#38bdf8");
        scrWrap.appendChild(imgHolder);
        div.appendChild(scrWrap);
      }

      el.actionLogList.appendChild(div);
    }
  }

  function buildFullPlainLogText() {
    const modeVal = (el.solveModeSelect && el.solveModeSelect.value) ? el.solveModeSelect.value : "balanced";
    const lines = [
      `=== ЛОГ РЕШАЛКИ «ДВОЙНАЯ РАДОСТЬ» (Этап ${state.stageNo}, Текущий счёт: ${state.curScore}, Режим: ${modeVal}) ===`,
    ];
    for (const item of state.actionLog) {
      lines.push(`[${item.fullTs || item.time}] [#${item.seq}] [${item.tag}] ${item.plain}`);
      if (item.uiScreenId) {
        lines.push(`    -> 🪟 СКРИН С ОКОШКОМ #${item.seq}: ${item.uiScreenId}`);
      }
      if (item.screenId) {
        lines.push(`    -> 📸 СКРИН ДЕЙСТВИЯ #${item.seq}: ${item.screenId}`);
      }
      for (const sl of item.subLinesPlain || []) {
        lines.push(`    -> ${sl}`);
      }
    }
    return lines.join("\\n");
  }"""
    if old_render_log_tail not in html:
        raise RuntimeError("Expected renderActionLog / buildFullPlainLogText tail in source HTML")
    html = html.replace(old_render_log_tail, new_render_log_tail, 1)

    # Also attach screenshot when importing via 📷 Скрин in main app & clean screens on 🗑 Clear Log
    old_cv_apply_log = """    appendLog({
      cat: "cv",
      tag: handOnly ? "РУКА-СКАН" : "СКРИНШОТ","""
    new_cv_apply_log = """    let importedScrId = null;
    try {
      if (state.cvCanvas && window.AndroidHost && typeof window.AndroidHost.saveWebScreenDataUri === "function") {
        importedScrId = window.AndroidHost.saveWebScreenDataUri(
          state.cvCanvas.toDataURL("image/jpeg", 0.8),
          handOnly ? "РУКА_СКРИН" : "ПОЛЕ_СКРИН"
        ) || null;
      }
    } catch (_) {}
    appendLog({
      cat: "cv",
      screenId: importedScrId,
      tag: handOnly ? "РУКА-СКАН" : "СКРИНШОТ","""
    if old_cv_apply_log not in html:
        raise RuntimeError("Expected applyRecognizedCvToBoard appendLog in source HTML")
    html = html.replace(old_cv_apply_log, new_cv_apply_log, 1)

    old_clear_log = """    if (el.btnClearLog) {
      el.btnClearLog.addEventListener("click", () => {
        state.actionLog = [];
        state.logSeq = 0;"""
    new_clear_log = """    if (el.btnClearLog) {
      el.btnClearLog.addEventListener("click", () => {
        state.actionLog = [];
        state.logSeq = 0;
        if (window.AndroidHost && typeof window.AndroidHost.clearLogScreens === "function") {
          try { window.AndroidHost.clearLogScreens(); } catch (_) {}
        }"""
    if old_clear_log not in html:
        raise RuntimeError("Expected btnClearLog handler in source HTML")
    html = html.replace(old_clear_log, new_clear_log, 1)

    old_hand_stats_loop = """    for (const item of state.actionLog) {
      if (item.plain) lines.push(`[${item.tag}] ${item.plain}`);
    }"""
    new_hand_stats_loop = """    for (const item of state.actionLog) {
      if (item.plain) lines.push(`[${item.tag}] ${item.plain}`);
      if (Array.isArray(item.subLinesPlain)) {
        for (const sl of item.subLinesPlain) lines.push(sl);
      }
    }"""
    if old_hand_stats_loop not in html:
        raise RuntimeError("Expected computeLogHandStats loop in source HTML")
    html = html.replace(old_hand_stats_loop, new_hand_stats_loop, 1)

    old_copy_log = """    if (el.btnCopyLog) {
      el.btnCopyLog.addEventListener("click", async () => {
        try {
          await navigator.clipboard.writeText(buildFullPlainLogText());
          el.btnCopyLog.textContent = "✓ Скопировано";
          setTimeout(() => {
            el.btnCopyLog.textContent = "📋 Копия";
          }, 1400);
        } catch (_) {}
      });
    }"""
    new_copy_log = """    if (el.btnCopyLog) {
      el.btnCopyLog.addEventListener("click", async () => {
        const txt = buildFullPlainLogText();
        if (window.AndroidHost && typeof window.AndroidHost.copyToClipboard === "function") {
          if (window.AndroidHost.copyToClipboard(txt)) {
            el.btnCopyLog.textContent = "✓ Скопировано";
            setTimeout(() => { el.btnCopyLog.textContent = "📋 Копия"; }, 1400);
            return;
          }
        }
        try {
          await navigator.clipboard.writeText(txt);
          el.btnCopyLog.textContent = "✓ Скопировано";
          setTimeout(() => {
            el.btnCopyLog.textContent = "📋 Копия";
          }, 1400);
        } catch (_) {}
      });
    }"""
    if old_copy_log not in html:
        raise RuntimeError("Expected btnCopyLog handler in source HTML")
    html = html.replace(old_copy_log, new_copy_log, 1)

    old_dl_log = """        const text = buildFullPlainLogText();
        const blob = new Blob([text], { type: "text/plain;charset=utf-8" });"""
    new_dl_log = """        if (typeof window.apkTriggerLogZipExport === "function" && window.AndroidHost && (typeof window.AndroidHost.exportLogHtmlBundle === "function" || typeof window.AndroidHost.exportLogZipBundle === "function")) {
          window.apkTriggerLogZipExport();
          return;
        }
        const text = buildFullPlainLogText();
        const fName = `Лог_Двойная_радость_Этап${state.stageNo}.log`;
        if (window.AndroidHost && typeof window.AndroidHost.saveExportFile === "function") {
          if (window.AndroidHost.saveExportFile(fName, text, "text/plain")) return;
        }
        const blob = new Blob([text], { type: "text/plain;charset=utf-8" });"""
    if old_dl_log not in html:
        raise RuntimeError("Expected btnDownloadLog handler in source HTML")
    html = html.replace(old_dl_log, new_dl_log, 1)

    old_dl_json = """    const blob = new Blob([JSON.stringify(payload, null, 2)], { type: "application/json;charset=utf-8" });"""
    new_dl_json = """    const jsonStrOut = JSON.stringify(payload, null, 2);
    const jsonFileName = `Двойная_радость_Этап${state.stageNo}_Счёт${state.curScore}.json`;
    if (window.AndroidHost && typeof window.AndroidHost.saveExportFile === "function") {
      if (window.AndroidHost.saveExportFile(jsonFileName, jsonStrOut, "application/json")) return;
    }
    const blob = new Blob([jsonStrOut], { type: "application/json;charset=utf-8" });"""
    if old_dl_json not in html:
        raise RuntimeError("Expected exportProgressToFile blob in source HTML")
    html = html.replace(old_dl_json, new_dl_json, 1)

    # 7. Eliminate WebView <-> Overlay race echo & hook multi-event logEntries + pendingDiff into NativeBridge
    old_sync_host = """  function syncStateToAndroidHost() {
    if (typeof window === "undefined" || !window.AndroidHost || !window.AndroidHost.onWebStateChanged) return;
    try {
      const cal = state.cvAnalysis && state.cvAnalysis.calibration ? state.cvAnalysis.calibration : null;
      window.AndroidHost.onWebStateChanged(
        JSON.stringify({
          stageNo: state.stageNo,
          curScore: state.curScore,
          rotationDeg: state.rotationDeg,
          mode: el.solveModeSelect ? el.solveModeSelect.value : "balanced",
          cells: captureProgressSnapshot().cells,
          waits: state.waits,
          calibration: cal,
          customPalette: state.customPalette,
        })
      );
    } catch (_) {}
  }"""
    new_sync_host = """  function syncStateToAndroidHost() {
    if (typeof window === "undefined" || !window.AndroidHost || !window.AndroidHost.onWebStateChanged) return;
    if (window._apkApplyingNativeSnapshot) return;
    try {
      const cal = state.cvAnalysis && state.cvAnalysis.calibration ? state.cvAnalysis.calibration : null;
      window.AndroidHost.onWebStateChanged(
        JSON.stringify({
          baseStateSeq: Number(window._apkLastNativeStateSeq || 0),
          stageNo: state.stageNo,
          curScore: state.curScore,
          rotationDeg: state.rotationDeg,
          mode: el.solveModeSelect ? el.solveModeSelect.value : "balanced",
          cells: captureProgressSnapshot().cells,
          waits: state.waits,
          calibration: cal,
          customPalette: state.customPalette,
        })
      );
    } catch (_) {}
  }"""
    if old_sync_host not in html:
        raise RuntimeError("Expected syncStateToAndroidHost in source HTML")
    html = html.replace(old_sync_host, new_sync_host, 1)

    old_nb_head = """      receiveNativeSnapshot(payload) {
        if (!payload) return;
        const obj = typeof payload === "string" ? JSON.parse(payload) : payload;"""
    new_nb_head = """      receiveNativeSnapshot(payload) {
        if (!payload) return;
        window._apkApplyingNativeSnapshot = true;
        try {
        const obj = typeof payload === "string" ? JSON.parse(payload) : payload;
        if (obj.stateSeq || (obj.snapshot && obj.snapshot.stateSeq)) {
          window._apkLastNativeStateSeq = Number(obj.stateSeq || (obj.snapshot && obj.snapshot.stateSeq) || 0);
        }"""
    if old_nb_head not in html:
        raise RuntimeError("Expected receiveNativeSnapshot head in source HTML")
    html = html.replace(old_nb_head, new_nb_head, 1)

    old_nb = """        if (obj.logEntry) {
          appendLog(obj.logEntry);
        }
        renderAll();
        runSolver();"""
    new_nb = """        if (obj.mode && el.solveModeSelect) {
          el.solveModeSelect.value = obj.mode;
          try { localStorage.setItem(SOLVE_MODE_STORAGE_KEY, obj.mode); } catch (_) {}
          updateSolveModeDesc();
        }
        window.apkJustChangedStage = true;
        if (Array.isArray(obj.logEntries) && obj.logEntries.length > 0) {
          for (const le of obj.logEntries) {
            const cp = le.checkpointSnapshot ? convertNativeSnapshotToWebCheckpoint(le.checkpointSnapshot) : null;
            appendLog({
              cat: le.cat || "cv",
              tag: le.tag || "ИНФО",
              html: le.html || "",
              plain: le.plain || "",
              subLinesHtml: Array.isArray(le.subLinesHtml) ? le.subLinesHtml : [],
              subLinesPlain: Array.isArray(le.subLinesPlain) ? le.subLinesPlain : [],
              withCheckpoint: !cp,
              time: le.time || null,
              fullTs: le.fullTs || null,
              customCheckpoint: cp,
              screenId: le.screenId || null,
              uiScreenId: le.uiScreenId || null,
              skipRenderSave: true,
            });
          }
          renderActionLog();
          saveAutoProgress();
        } else if (obj.logEntry) {
          appendLog(obj.logEntry);
        }
        if (obj.pendingDiff && obj.pendingDiff.hasPending) {
          window.apkPendingDiff = obj.pendingDiff;
        } else if (obj.pendingDiff && obj.pendingDiff.hasPending === false) {
          window.apkPendingDiff = null;
        }
        renderAll();
        runSolver();
        if (window.apkPendingDiff && window.apkPendingDiff.hasPending && (
          Array.isArray(window.apkPendingDiff.diffs) && window.apkPendingDiff.diffs.length > 0
        )) {
          if (typeof window.apkOpenSheet === "function") window.apkOpenSheet("diff");
        }
        } finally {
          window._apkApplyingNativeSnapshot = false;
        }"""
    if old_nb not in html:
        raise RuntimeError("Expected NativeBridge.receiveNativeSnapshot tail in source HTML")
    html = html.replace(old_nb, new_nb, 1)

    # 8. Inject Phone Single-Screen + Bottom Sheet + Board Diff runtime at end of init()
    old_init_end = """      appendLog({
        cat: "save",
        tag: "СТАРТ",
        html: `Сессия запущена (Этап ${state.stageNo}, Счёт ${state.curScore}). Автосохранение включено.`,
        plain: `Сессия запущена (Этап ${state.stageNo}, Счёт ${state.curScore}). Автосохранение включено.`,
      });
    }
  }"""
    new_init_end = old_init_end[:-4] + "\n" + APK_PHONE_RUNTIME_JS + "\n  }"
    if old_init_end not in html:
        raise RuntimeError("Expected init() end block in source HTML")
    html = html.replace(old_init_end, new_init_end, 1)

    # 9. Lock viewport scale cleanly for Android WebView and inject APK-only CSS before </head>
    html = html.replace(
        '<meta name="viewport" content="width=device-width, initial-scale=1.0" />',
        '<meta name="viewport" content="width=device-width, initial-scale=1.0, maximum-scale=1.0, user-scalable=no, viewport-fit=cover" />',
        1,
    )
    if "</head>" not in html:
        raise RuntimeError("Expected '</head>' in source HTML")
    html = html.replace("</head>", APK_CUSTOM_CSS + "\n</head>", 1)
    html = html.replace("__SUD_CREST_DATA_URI__", crest_uri)

    DST.parent.mkdir(parents=True, exist_ok=True)
    # --- подстановка встроенного PAT из .builtin_pat (секрет только локально) ---
    try:
        import pathlib as _pl
        _pf = _pl.Path(__file__).resolve().parent / ".builtin_pat"
        _pat = _pf.read_text(encoding="utf-8").strip() if _pf.exists() else ""
        if _pat:
            html = html.replace('const GH_BUILTIN_PAT = "";', 'const GH_BUILTIN_PAT = "' + _pat + '";')
    except Exception:
        pass
    DST.write_text(html, encoding="utf-8")
    print(f"Wrote APK-exclusive {DST} ({DST.stat().st_size / 1024:.1f} KB)")


if __name__ == "__main__":
    main()
