package ru.doublejoy.solver;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Shared live state synchronized between MainActivity (WebView UI) and CaptureOverlayService.
 */
public final class AppState {

    private static final AppState INSTANCE = new AppState();

    public static AppState get() {
        return INSTANCE;
    }

    public int stageNo = 7;
    public int curScore = 0;
    public int rotationDeg = 0;
    public String mode = "balanced";
    public SolverEngine.CellState[] cells;
    public Map<Integer, List<SolverEngine.Layer>> waits = new HashMap<>();
    public SolverEngine.Calibration userCalibration;
    public Map<Integer, int[]> customPalette = new HashMap<>();
    public SolverEngine.SolvePlan lastPlan;
    public SolverEngine.BoardDiffResult pendingDiffResult;
    public SolverEngine.ScreenBoardGeometry screenGeo;
    public int overlayDisplayMode = 0; // 0 = Combo (AR + Bottom Strip), 1 = AR Only, 2 = Top Dock Only
    public boolean overlayStripSortByStep = false; // false = Mode A (over own stack), true = Mode B (Step 1->2->3)
    public int inspectedOverlayCellId = -1;
    public String dismissedModeAdviceKey = "";
    public boolean justScannedStage = true;
    public long stateSeq = 1L;
    public String scoreMismatchWarning = "";
    public boolean useHintsInCalc = false;
    // Инвентарь бустеров игрока по скрину: -1 = панель не распознана, 0 = бустера нет
    public int boostHammer = -1;
    public int boostMove = -1;
    public int boostRefresh = -1;
    public synchronized void setBoosters(int hammer, int move, int refresh) {
        boostHammer = hammer;
        boostMove = move;
        boostRefresh = refresh;
    }
    public boolean saveUiScreenshots = false;
    public final List<JSONObject> pendingLogQueue = new ArrayList<>();

    // Frame-to-frame pixel-delta filter state
    public int[] prevFrameArgb;
    public int prevFrameW;
    public int prevFrameH;
    public boolean hasPrevFrame;
    public final List<SolverEngine.CausalHistoryStep> causalHistory = new ArrayList<>();
    public String lastScreenshotDataUri;
    public JSONObject lastCvAnalysisJson;
    public boolean hasPendingNativeUpdate = false;

    public synchronized void pushCausalHistoryStep(SolverEngine.CausalHistoryStep step) {
        if (step == null) return;
        causalHistory.add(step);
        while (causalHistory.size() > 24) {
            causalHistory.remove(0);
        }
    }

    private AppState() {
        waits.put(1, new ArrayList<>());
        waits.put(2, new ArrayList<>());
        waits.put(3, new ArrayList<>());
    }

    public synchronized void ensureInitialized(Context ctx) {
        if (cells == null) {
            Map<Integer, SolverEngine.StageCfg> map = SolverEngine.getStages(ctx);
            SolverEngine.StageCfg stg = map.containsKey(stageNo) ? map.get(stageNo) : map.get(1);
            if (stg != null) {
                cells = SolverEngine.createStageBoard(stg, curScore);
            }
        }
    }

    public synchronized void changeStage(Context ctx, int newStageNo) {
        Map<Integer, SolverEngine.StageCfg> map = SolverEngine.getStages(ctx);
        if (newStageNo < 1) newStageNo = 16;
        if (newStageNo > 16) newStageNo = 1;
        stageNo = newStageNo;
        curScore = 0;
        rotationDeg = 0;
        userCalibration = null;
        screenGeo = null;
        inspectedOverlayCellId = -1;
        dismissedModeAdviceKey = "";
        justScannedStage = true;
        scoreMismatchWarning = "";
        stateSeq++;
        // Reset frame-delta keeper on stage change (geometry changes)
        prevFrameArgb = null;
        hasPrevFrame = false;
        SolverEngine.StageCfg stg = map.get(stageNo);
        if (stg != null) {
            cells = SolverEngine.createStageBoard(stg, 0);
        }
        waits.put(1, new ArrayList<>());
        waits.put(2, new ArrayList<>());
        waits.put(3, new ArrayList<>());
        lastPlan = null;
        pendingDiffResult = null;
        causalHistory.clear();
        enqueueLogEvent(
                "save",
                "ЭТАП",
                "🔄 Загружен Этап " + stageNo + " (цель " + (stg != null ? stg.targetScore : 0) + ", Счёт 0)",
                null,
                true
        );
        hasPendingNativeUpdate = true;
    }

    public synchronized JSONObject buildCurrentSnapshotJson() {
        JSONObject snap = new JSONObject();
        try {
            snap.put("version", 1);
            snap.put("stateSeq", stateSeq);
            snap.put("stageNo", stageNo);
            snap.put("curScore", curScore);
            snap.put("rotationDeg", rotationDeg);

            JSONObject cellsMap = new JSONObject();
            if (cells != null) {
                for (int cid = 1; cid <= SolverEngine.MAX_POS_COUNT; cid++) {
                    SolverEngine.CellState cs = cells[cid];
                    if (cs != null && cs.exist == 1) {
                        JSONArray la = new JSONArray();
                        for (SolverEngine.Layer l : cs.layers) {
                            JSONObject lo = new JSONObject();
                            lo.put("colour", l.colour);
                            lo.put("count", l.count);
                            la.put(lo);
                        }
                        JSONObject cellObj = new JSONObject();
                        cellObj.put("cellId", cid);
                        cellObj.put("exist", 1);
                        cellObj.put("lockScore", cs.lockScore);
                        cellObj.put("lockCardScore", cs.lockCardScore);
                        cellObj.put("layers", la);
                        cellsMap.put(String.valueOf(cid), cellObj);
                    }
                }
            }
            snap.put("cells", cellsMap);

            JSONObject waitsMap = new JSONObject();
            for (int sid = 1; sid <= 3; sid++) {
                JSONArray la = new JSONArray();
                List<SolverEngine.Layer> wl = waits.get(sid);
                if (wl != null) {
                    for (SolverEngine.Layer l : wl) {
                        JSONObject lo = new JSONObject();
                        lo.put("colour", l.colour);
                        lo.put("count", l.count);
                        la.put(lo);
                    }
                }
                waitsMap.put(String.valueOf(sid), la);
            }
            snap.put("waits", waitsMap);
        } catch (Exception ignored) {}
        return snap;
    }

    public synchronized void enqueueLogEvent(
            String cat,
            String tag,
            String html,
            List<String> subLines,
            boolean withCheckpoint
    ) {
        enqueueLogEvent(cat, tag, html, subLines, withCheckpoint, null);
    }

    public synchronized void enqueueLogEvent(
            String cat,
            String tag,
            String html,
            List<String> subLines,
            boolean withCheckpoint,
            String screenId
    ) {
        enqueueLogEvent(cat, tag, html, subLines, withCheckpoint, screenId, null);
    }

    public synchronized void enqueueLogEvent(
            String cat,
            String tag,
            String html,
            List<String> subLines,
            boolean withCheckpoint,
            String screenId,
            String uiScreenId
    ) {
        if (html == null || html.isEmpty()) return;
        try {
            Date now = new Date();
            String fullTs = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(now);
            String shortTime = new SimpleDateFormat("HH:mm:ss", Locale.US).format(now);

            JSONObject le = new JSONObject();
            le.put("cat", cat != null ? cat : "cv");
            le.put("tag", tag != null ? tag : "ИНФО");
            le.put("time", shortTime);
            le.put("fullTs", fullTs);
            le.put("html", html);
            le.put("plain", html.replaceAll("<[^>]+>", ""));
            if (screenId != null && !screenId.isEmpty()) {
                le.put("screenId", screenId);
            }
            if (uiScreenId != null && !uiScreenId.isEmpty()) {
                le.put("uiScreenId", uiScreenId);
            }
            if (subLines != null && !subLines.isEmpty()) {
                JSONArray sh = new JSONArray();
                JSONArray sp = new JSONArray();
                for (String s : subLines) {
                    sh.put(s);
                    sp.put(s.replaceAll("<[^>]+>", ""));
                }
                le.put("subLinesHtml", sh);
                le.put("subLinesPlain", sp);
            }
            if (withCheckpoint) {
                le.put("checkpointSnapshot", buildCurrentSnapshotJson());
            }
            pendingLogQueue.add(le);
            if (pendingLogQueue.size() > 500) {
                pendingLogQueue.remove(0);
            }
        } catch (Exception ignored) {}
    }

    public synchronized int applySelectedPendingDiffs(Context ctx) {
        ensureInitialized(ctx);
        if (pendingDiffResult == null) return 0;
        Map<Integer, SolverEngine.StageCfg> map = SolverEngine.getStages(ctx);
        SolverEngine.StageCfg stg = map.containsKey(stageNo) ? map.get(stageNo) : map.get(1);

        int applied = 0;
        List<String> subLines = new ArrayList<>();
        for (SolverEngine.CellDiff d : pendingDiffResult.diffs) {
            if (!d.selected) continue;
            if (d.cellId >= 1 && d.cellId <= SolverEngine.MAX_POS_COUNT && cells[d.cellId] != null && cells[d.cellId].exist == 1) {
                String beforeStr = SolverEngine.formatLayersPlain(cells[d.cellId].layers);
                cells[d.cellId].layers.clear();
                for (SolverEngine.Layer l : d.gameLayers) {
                    cells[d.cellId].layers.add(l.copy());
                }
                cells[d.cellId].normalize();
                String afterStr = SolverEngine.formatLayersPlain(cells[d.cellId].layers);
                subLines.add("• Клетка #" + d.cellId + ": " + beforeStr + " → " + afterStr);
                applied++;
            }
        }
        if (pendingDiffResult.detectedScore >= 0) {
            curScore = pendingDiffResult.detectedScore;
            for (int cid = 1; cid <= SolverEngine.MAX_POS_COUNT; cid++) {
                SolverEngine.CellState cs = cells[cid];
                if (cs != null && cs.exist == 1) {
                    if (cs.lockScore > 0 && curScore >= cs.lockScore) cs.lockScore = 0;
                    if (cs.lockCardScore > 0 && curScore >= cs.lockCardScore) cs.lockCardScore = 0;
                }
            }
        }
        if (pendingDiffResult.scannedWaits != null && !pendingDiffResult.scannedWaits.isEmpty()) {
            boolean anyWait = false;
            for (int sid = 1; sid <= 3; sid++) {
                List<SolverEngine.Layer> wl = pendingDiffResult.scannedWaits.get(sid);
                if (wl != null && !wl.isEmpty()) { anyWait = true; break; }
            }
            if (anyWait) {
                for (int sid = 1; sid <= 3; sid++) {
                    List<SolverEngine.Layer> wl = pendingDiffResult.scannedWaits.get(sid);
                    List<SolverEngine.Layer> copy = new ArrayList<>();
                    if (wl != null) for (SolverEngine.Layer l : wl) copy.add(l.copy());
                    waits.put(sid, copy);
                }
            }
        }

        int anglesCount = pendingDiffResult.scannedAngles != null ? Math.max(1, pendingDiffResult.scannedAngles.size()) : 1;
        String diffScreenId = pendingDiffResult.lastScreenId;
        String diffUiScreenId = pendingDiffResult.uiScreenId;
        SolverEngine.VisionOutput latestVo = pendingDiffResult.latestVisionOutput;
        int latestRot = pendingDiffResult.rotationDeg;
        String diagNote = pendingDiffResult.causalDiagnosisRu != null ? pendingDiffResult.causalDiagnosisRu : "";
        if (!diagNote.isEmpty()) {
            subLines.add(0, diagNote);
        }
        pendingDiffResult = null;
        scoreMismatchWarning = "";
        stateSeq++;
        if (stg != null) {
            lastPlan = SolverEngine.solveBestPlan(cells, waits, curScore, stg.targetScore, mode, stageNo, false);
        }
        pushCausalHistoryStep(SolverEngine.CausalHistoryStep.fromVision(
                (int) stateSeq, "СВЕРКА", curScore, curScore, curScore,
                cells, cells, waits, lastPlan, latestVo, latestRot
        ));
        enqueueLogEvent(
                "cv",
                "СВЕРКА",
                "🔍 Утверждена синхронизация поля с игрой (Этап " + stageNo + ", Счёт " + curScore
                        + (rotationDeg != 0 ? (", ракурс " + rotationDeg + "°") : "")
                        + (anglesCount > 1 ? (", ракурсов: " + anglesCount) : "")
                        + ", изменено клеток: <b>" + applied + "</b>)",
                subLines,
                true,
                diffScreenId,
                diffUiScreenId
        );
        hasPendingNativeUpdate = true;
        return applied;
    }

    public synchronized void discardPendingDiffs() {
        pendingDiffResult = null;
        stateSeq++;
        hasPendingNativeUpdate = true;
    }

    public synchronized void setUseHintsInCalc(boolean value) {
        useHintsInCalc = value;
    }

    public synchronized void setSaveUiScreenshots(boolean value) {
        saveUiScreenshots = value;
    }

    public synchronized String formatBoardSummaryPlain() {
        if (cells == null) return "пусто";
        StringBuilder sb = new StringBuilder();
        int occ = 0, emp = 0;
        for (int cid = 1; cid <= SolverEngine.MAX_POS_COUNT; cid++) {
            SolverEngine.CellState cs = cells[cid];
            if (cs == null || cs.exist != 1) continue;
            if (cs.isEmptyPlaceable()) emp++;
            else if (!cs.isLocked() && cs.hasBlocks()) {
                if (occ > 0) sb.append(", ");
                sb.append("#").append(cid).append("(").append(SolverEngine.formatLayersPlain(cs.layers)).append(")");
                occ++;
            }
        }
        return "Стопок: " + occ + ", Пусто: " + emp + (occ > 0 ? " | " + sb.toString() : "");
    }

    public synchronized void applyLastPlanMoves() {
        if (lastPlan == null || lastPlan.steps == null || lastPlan.steps.isEmpty()) return;
        SolverEngine.StepMove last = lastPlan.steps.get(lastPlan.steps.size() - 1);
        if (last.boardAfter != null) {
            cells = SolverEngine.cloneBoard(last.boardAfter);
            curScore = last.scoreAfter;
        }
        for (SolverEngine.StepMove sm : lastPlan.steps) {
            waits.put(sm.slotId, new ArrayList<>());
        }
        lastPlan = null;
        stateSeq++;
        hasPendingNativeUpdate = true;
    }

    public synchronized void updateFromWebJson(Context ctx, String jsonStr) {
        ensureInitialized(ctx);
        try {
            JSONObject obj = new JSONObject(jsonStr);
            // Reject delayed/stale WebView background echoes if native overlay state has advanced!
            long baseSeq = obj.optLong("baseStateSeq", -1L);
            if (baseSeq >= 0 && baseSeq < stateSeq) {
                return;
            }
            if (hasPendingNativeUpdate && !obj.optBoolean("forceUserEdit", false)) {
                return;
            }
            scoreMismatchWarning = "";
            stateSeq++;
            int newStage = obj.optInt("stageNo", stageNo);
            int newScore = Math.max(0, obj.optInt("curScore", curScore));
            rotationDeg = obj.optInt("rotationDeg", rotationDeg);
            mode = obj.optString("mode", mode);

            Map<Integer, SolverEngine.StageCfg> map = SolverEngine.getStages(ctx);
            SolverEngine.StageCfg stg = map.containsKey(newStage) ? map.get(newStage) : map.get(1);
            stageNo = stg.stageNo;
            curScore = newScore;
            cells = SolverEngine.createStageBoard(stg, curScore);

            JSONObject cellsObj = obj.optJSONObject("cells");
            if (cellsObj != null) {
                Iterator<String> keys = cellsObj.keys();
                while (keys.hasNext()) {
                    String k = keys.next();
                    int cid;
                    try {
                        cid = Integer.parseInt(k);
                    } catch (NumberFormatException nfe) {
                        continue;
                    }
                    if (cid >= 1 && cid <= SolverEngine.MAX_POS_COUNT && cells[cid] != null && cells[cid].exist == 1) {
                        // Support BOTH { cellId, layers: [...] } object format from solver.html AND raw [...] array
                        JSONArray arr = cellsObj.optJSONArray(k);
                        if (arr == null) {
                            JSONObject cellWrap = cellsObj.optJSONObject(k);
                            if (cellWrap != null) {
                                arr = cellWrap.optJSONArray("layers");
                            }
                        }
                        cells[cid].layers.clear();
                        if (arr != null) {
                            for (int i = 0; i < arr.length(); i++) {
                                JSONObject lo = arr.optJSONObject(i);
                                if (lo != null) {
                                    cells[cid].layers.add(new SolverEngine.Layer(lo.getInt("colour"), lo.getInt("count")));
                                }
                            }
                        }
                        cells[cid].normalize();
                    }
                }
            }

            JSONObject waitsObj = obj.optJSONObject("waits");
            if (waitsObj != null) {
                for (int sid = 1; sid <= 3; sid++) {
                    JSONArray arr = waitsObj.optJSONArray(String.valueOf(sid));
                    List<SolverEngine.Layer> list = new ArrayList<>();
                    if (arr != null) {
                        for (int i = 0; i < arr.length(); i++) {
                            JSONObject lo = arr.optJSONObject(i);
                            if (lo != null) {
                                list.add(new SolverEngine.Layer(lo.getInt("colour"), lo.getInt("count")));
                            }
                        }
                    }
                    waits.put(sid, list);
                }
            }

            if (obj.optBoolean("clearPendingDiff", false)) {
                pendingDiffResult = null;
            }

            // Recompute best plan so floating overlay mini-map & moves stay 1:1 in sync with manual board edits
            lastPlan = SolverEngine.solveBestPlan(cells, waits, curScore, stg.targetScore, mode, stageNo, justScannedStage);

            JSONObject calObj = obj.optJSONObject("calibration");
            if (calObj != null && calObj.has("scaleX")) {
                SolverEngine.Calibration cal = new SolverEngine.Calibration();
                cal.centerX = calObj.getDouble("centerX");
                cal.centerY = calObj.getDouble("centerY");
                cal.scaleX = calObj.getDouble("scaleX");
                cal.scaleY = calObj.getDouble("scaleY");
                cal.trayY = calObj.getDouble("trayY");
                cal.traySpanX = calObj.optDouble("traySpanX", 0.255);
                userCalibration = cal;
            }

            JSONObject palObj = obj.optJSONObject("customPalette");
            customPalette.clear();
            if (palObj != null) {
                Iterator<String> pk = palObj.keys();
                while (pk.hasNext()) {
                    String k = pk.next();
                    JSONArray rgb = palObj.optJSONArray(k);
                    if (rgb != null && rgb.length() >= 3) {
                        customPalette.put(Integer.parseInt(k), new int[]{rgb.getInt(0), rgb.getInt(1), rgb.getInt(2)});
                    }
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public synchronized String solveWebJson(Context ctx, String jsonStr) {
        ensureInitialized(ctx);
        try {
            JSONObject obj = new JSONObject(jsonStr);
            int stgNo = obj.optInt("stageNo", stageNo);
            int reqScore = Math.max(0, obj.optInt("curScore", curScore));
            String reqMode = obj.optString("mode", mode);
            int topK = Math.max(1, Math.min(5, obj.optInt("topK", 3)));

            Map<Integer, SolverEngine.StageCfg> map = SolverEngine.getStages(ctx);
            SolverEngine.StageCfg stg = map.containsKey(stgNo) ? map.get(stgNo) : map.get(1);
            SolverEngine.CellState[] tmpCells = SolverEngine.createStageBoard(stg, reqScore);

            JSONObject cellsObj = obj.optJSONObject("cells");
            if (cellsObj != null) {
                Iterator<String> keys = cellsObj.keys();
                while (keys.hasNext()) {
                    String k = keys.next();
                    int cid;
                    try { cid = Integer.parseInt(k); } catch (NumberFormatException nfe) { continue; }
                    if (cid >= 1 && cid <= SolverEngine.MAX_POS_COUNT && tmpCells[cid] != null && tmpCells[cid].exist == 1) {
                        JSONArray arr = cellsObj.optJSONArray(k);
                        if (arr == null) {
                            JSONObject cellWrap = cellsObj.optJSONObject(k);
                            if (cellWrap != null) {
                                arr = cellWrap.optJSONArray("layers");
                                if (cellWrap.has("lockScore")) tmpCells[cid].lockScore = cellWrap.optInt("lockScore", tmpCells[cid].lockScore);
                                if (cellWrap.has("lockCardScore")) tmpCells[cid].lockCardScore = cellWrap.optInt("lockCardScore", tmpCells[cid].lockCardScore);
                            }
                        }
                        tmpCells[cid].layers.clear();
                        if (arr != null) {
                            for (int i = 0; i < arr.length(); i++) {
                                JSONObject lo = arr.optJSONObject(i);
                                if (lo != null) {
                                    tmpCells[cid].layers.add(new SolverEngine.Layer(lo.getInt("colour"), lo.getInt("count")));
                                }
                            }
                        }
                        tmpCells[cid].normalize();
                    }
                }
            }

            Map<Integer, List<SolverEngine.Layer>> reqWaits = new HashMap<>();
            JSONObject waitsObj = obj.optJSONObject("waits");
            for (int sid = 1; sid <= 3; sid++) {
                List<SolverEngine.Layer> list = new ArrayList<>();
                if (waitsObj != null) {
                    JSONArray arr = waitsObj.optJSONArray(String.valueOf(sid));
                    if (arr != null) {
                        for (int i = 0; i < arr.length(); i++) {
                            JSONObject lo = arr.optJSONObject(i);
                            if (lo != null) {
                                list.add(new SolverEngine.Layer(lo.getInt("colour"), lo.getInt("count")));
                            }
                        }
                    }
                }
                reqWaits.put(sid, list);
            }

            boolean isFresh = obj.optBoolean("isFreshStageScan", false);
            return SolverEngine.solvePlansToJson(tmpCells, reqWaits, reqScore, stg.targetScore, reqMode, topK, stgNo, isFresh);
        } catch (Exception e) {
            return "{\"plans\":[],\"error\":\"" + String.valueOf(e.getMessage()).replace("\"", "'") + "\"}";
        }
    }

    public synchronized JSONObject buildWebSyncPayload() {
        JSONObject payload = new JSONObject();
        try {
            payload.put("stateSeq", stateSeq);
            payload.put("snapshot", buildCurrentSnapshotJson());
            payload.put("mode", mode);

            if (pendingDiffResult != null) {
                payload.put("pendingDiff", SolverEngine.boardDiffToJson(pendingDiffResult));
            } else {
                JSONObject noneDiff = new JSONObject();
                noneDiff.put("hasPending", false);
                payload.put("pendingDiff", noneDiff);
            }

            if (lastScreenshotDataUri != null) {
                payload.put("screenshotDataUri", lastScreenshotDataUri);
            }
            if (lastCvAnalysisJson != null) {
                payload.put("cvAnalysis", lastCvAnalysisJson);
            }

            if (!pendingLogQueue.isEmpty()) {
                JSONArray logArr = new JSONArray();
                for (JSONObject le : pendingLogQueue) {
                    logArr.put(le);
                }
                pendingLogQueue.clear();
                payload.put("logEntries", logArr);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return payload;
    }
}
