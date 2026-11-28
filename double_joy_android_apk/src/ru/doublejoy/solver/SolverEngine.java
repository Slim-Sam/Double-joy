package ru.doublejoy.solver;

import android.content.Context;
import android.graphics.Bitmap;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Pure Java 1:1 game simulator (CardMasterBoardSim), 1-round Expectimax Beam Solver,
 * and Stage-Centered Computer Vision screenshot analyzer.
 * Runs directly on Bitmap int[] pixels inside CaptureOverlayService in ~15ms with 0 WebView overhead.
 */
public final class SolverEngine {

    public static final int MAX_ROW = 7;
    public static final int MAX_POS_COUNT = 42;
    public static final int ELIMINATE_NUM = 10;
    private static final int[] SERIES_DISPEL = {0, 6, 12, 18, 24, 30, 36};

    public static final String[] SHORT_NAMES_RU = {
            "", "Ягоды", "Клён", "Колос", "Орёл", "Хлеб", "Яблоко", "Жёлудь", "Индейка"
    };

    public static final int[][] DEFAULT_PALETTE_RGB = {
            {0, 0, 0},
            {248, 78, 64},   // 1: Красный (Ягоды)
            {254, 142, 51},  // 2: Оранжевый (Клён)
            {249, 202, 46},  // 3: Жёлтый (Колос)
            {149, 235, 51},  // 4: Зелёный (Орёл)
            {58, 238, 205},  // 5: Голубой (Хлеб)
            {76, 191, 252},  // 6: Синий (Яблоко)
            {193, 107, 252}, // 7: Фиолетовый (Жёлудь)
            {254, 72, 135}   // 8: Розовый (Индейка)
    };

    public static final int[] CHIP_HEX_ARGB = {
            0xFF475569,
            0xFFF84E40,
            0xFFFE8E33,
            0xFFF9CA2E,
            0xFF95EB33,
            0xFF3AEECD,
            0xFF4CBFFC,
            0xFFC16BFC,
            0xFFFE4887
    };

    public static final int[][] ADJACENT_IDS = new int[MAX_POS_COUNT + 1][];
    static {
        for (int cid = 1; cid <= MAX_POS_COUNT; cid++) {
            int col = (cid + MAX_ROW - 1) / MAX_ROW;
            int[] raw;
            if (col % 2 == 1) {
                raw = new int[]{cid - MAX_ROW, cid - MAX_ROW + 1, cid + 1, cid + MAX_ROW + 1, cid + MAX_ROW, cid - 1};
            } else {
                raw = new int[]{cid - MAX_ROW - 1, cid - MAX_ROW, cid + 1, cid + MAX_ROW, cid + MAX_ROW - 1, cid - 1};
            }
            List<Integer> valid = new ArrayList<>(6);
            for (int v : raw) {
                if (v >= 1 && v <= MAX_POS_COUNT) valid.add(v);
            }
            int[] arr = new int[valid.size()];
            for (int i = 0; i < valid.size(); i++) arr[i] = valid.get(i);
            ADJACENT_IDS[cid] = arr;
        }
    }

    public static class Layer {
        public int colour;
        public int count;

        public Layer(int colour, int count) {
            this.colour = colour;
            this.count = count;
        }

        public Layer copy() {
            return new Layer(colour, count);
        }
    }

    public static class CellState {
        public int cellId;
        public int exist;
        public int lockScore;
        public int lockCardScore;
        public List<Layer> layers = new ArrayList<>();

        public CellState(int cellId, int exist) {
            this.cellId = cellId;
            this.exist = exist;
        }

        public CellState copy() {
            CellState c = new CellState(cellId, exist);
            c.lockScore = lockScore;
            c.lockCardScore = lockCardScore;
            for (Layer l : layers) c.layers.add(l.copy());
            return c;
        }

        public boolean isLocked() {
            return lockScore > 0 || lockCardScore > 0;
        }

        public boolean isEmptyPlaceable() {
            return exist == 1 && !isLocked() && layers.isEmpty();
        }

        public boolean hasBlocks() {
            return !layers.isEmpty();
        }

        public int topColor() {
            return layers.isEmpty() ? 0 : layers.get(layers.size() - 1).colour;
        }

        public int topCount() {
            return layers.isEmpty() ? 0 : layers.get(layers.size() - 1).count;
        }

        public int topNextColor() {
            return layers.size() < 2 ? 0 : layers.get(layers.size() - 2).colour;
        }

        public boolean isPure() {
            if (layers.isEmpty()) return false;
            int c0 = layers.get(0).colour;
            for (Layer l : layers) {
                if (l.colour != c0) return false;
            }
            return true;
        }

        public void normalize() {
            List<Layer> m = new ArrayList<>(layers.size());
            for (Layer l : layers) {
                if (l.colour <= 0 || l.count <= 0) continue;
                if (!m.isEmpty() && m.get(m.size() - 1).colour == l.colour) {
                    m.get(m.size() - 1).count += l.count;
                } else {
                    m.add(new Layer(l.colour, l.count));
                }
            }
            layers = m;
        }
    }

    public static class StageCellCfg {
        public int cellId;
        public int unlockCellScore;
        public int unlockCardScore;
        public List<Layer> initLayers = new ArrayList<>();
    }

    public static class StageCfg {
        public int stageNo;
        public int targetScore;
        public int boardType;
        public List<StageCellCfg> cells = new ArrayList<>();
    }

    public static class Calibration {
        public double centerX;
        public double centerY;
        public double scaleX;
        public double scaleY;
        public double trayY;
        public double traySpanX = 0.255;
        public double sliceStepPx = 5.5;

        public Calibration copy() {
            Calibration c = new Calibration();
            c.centerX = centerX;
            c.centerY = centerY;
            c.scaleX = scaleX;
            c.scaleY = scaleY;
            c.trayY = trayY;
            c.traySpanX = traySpanX;
            c.sliceStepPx = sliceStepPx;
            return c;
        }
    }

    public static class StepMove {
        public int stepNum;
        public int slotId;
        public int targetCellId;
        public List<Layer> cardLayers;
        public int gainedScore;
        public int scoreAfter;
        public int emptyAfter;
        public List<Integer> unlockedCells;
        public CellState[] boardAfter;
    }

    public static class ModeAdvice {
        public String suggestedMode = "";
        public String suggestedModeTitleRu = "";
        public String suggestedModeReasonRu = "";
        public boolean urgent = false;
    }

    public static class SolvePlan {
        public int rank;
        public double evalScore;
        public boolean isWin;
        public boolean recommendReroll;
        public boolean partialBeforeReroll = false;
        public boolean deadlockStopAll = false;
        /** Прогноз следующей руки из таблицы переходов — считается в ЛЮБОМ режиме. */
        public String predictedHandText = "";
        public List<List<Layer>> predictedNextHand = null;
        public String rerollReason = "";
        public int hammerCellId = 0;
        public String hammerReasonRu = "";
        public String suggestedMode = "";
        public String suggestedModeTitleRu = "";
        public String suggestedModeReasonRu = "";
        public boolean suggestedModeUrgent = false;
        public int finalScore;
        public int totalGainedScore;
        public int totalComboBonus;
        public int finalEmptyCells;
        public double mergeProbNextHand;
        public double burstProbNextHand;
        public List<Integer> openColors = new ArrayList<>();
        public List<Integer> burstReadyColors = new ArrayList<>();
        public List<String> walledWarnings = new ArrayList<>();
        public List<StepMove> steps = new ArrayList<>();
    }

    public static String getModeTitleRu(String mode) {
        if ("fast".equals(mode)) return "⚡ Быстрый";
        if ("survival".equals(mode)) return "🛡 Выживание";
        if ("deep".equals(mode)) return "🔬 Максимальный";
        return "⚖️ Оптимальный";
    }

    /**
     * Smart Stage-Aware & Pre-Deadlock Solver Mode Advisor.
     * 1) Pre-Deadlock (urgent = true): Detects 2-3 turns BEFORE board lockup (tight empty count,
     *    walled towers, low next-hand merge probability, or complex 3-layer late endgame) and
     *    proactively recommends escalating to «🛡 Выживание» or «🔬 Максимальный».
     * 2) Stage Scan / Stage Start (isFreshStageScan || curScore == 0): Analyzes exact stage
     *    anatomy (Stages 1..16 locks, pre-filled 4-layer towers, narrow 18-cell board, blitz goals)
     *    and suggests the optimal starting mode when different from currentMode.
     */
    public static ModeAdvice computeSolverModeAdvice(
            int stageNo,
            CellState[] board,
            int curScore,
            int targetScore,
            String currentMode,
            SolvePlan plan,
            boolean isFreshStageScan
    ) {
        ModeAdvice out = new ModeAdvice();
        // ВАЖНО: советы режимов (выживание/максимальный) — СОБСТВЕННЫЕ подсказки приложения
        // и работают ВСЕГДА. Кнопка «не учитывать подсказки» касается ТОЛЬКО игровых подсказок
        // (🔨 молоток, 🔄 обновить, переместить столбик) и их применённых эффектов.
        String curM = (currentMode == null || currentMode.isEmpty()) ? "balanced" : currentMode;
        if (plan != null && plan.isWin) return out;

        int totalCells = 0;
        int emptyNow = 0;
        int activeLocks = 0;
        int multiTowers = 0;
        if (board != null) {
            for (int cid = 1; cid <= MAX_POS_COUNT; cid++) {
                CellState c = board[cid];
                if (c == null || c.exist != 1) continue;
                totalCells++;
                boolean locked = (c.lockScore > curScore) || (c.lockCardScore > curScore);
                if (locked) activeLocks++;
                else if (!c.hasBlocks()) emptyNow++;
                else if (c.layers.size() >= 2) multiTowers++;
            }
        }

        int finalEmpty = (plan != null) ? plan.finalEmptyCells : emptyNow;
        int walledCnt = (plan != null && plan.walledWarnings != null) ? plan.walledWarnings.size() : 0;
        double mergeProb = (plan != null) ? plan.mergeProbNextHand : 60.0;
        double progress = targetScore > 0 ? (double) curScore / targetScore : 0.0;

        // ------------------------------------------------------------------
        // PRIORITY 1: Proactive Pre-Deadlock Detection (2-3 rounds early!)
        // ------------------------------------------------------------------
        if (plan != null && !plan.steps.isEmpty()) {
            // Severe tightening: <=3 empty left, OR <=4 empty with low merge/walled towers
            boolean severeCrunch = (finalEmpty >= 1 && finalEmpty <= 3)
                    || (finalEmpty == 4 && (emptyNow <= 5 || walledCnt >= 2 || mergeProb < 42.0));
            if (severeCrunch) {
                if ("fast".equals(curM) || "balanced".equals(curM)) {
                    out.suggestedMode = "survival";
                    out.suggestedModeTitleRu = getModeTitleRu("survival");
                    out.suggestedModeReasonRu = "⚠ Риск зажима поля (останется " + finalEmpty
                            + " пуст., сл.рука " + Math.round(mergeProb)
                            + "%) — заранее включите «🛡 Выживание» для расчистки клеток!";
                    out.urgent = true;
                    return out;
                } else if ("survival".equals(curM) && (finalEmpty <= 2 || (finalEmpty == 3 && mergeProb < 38.0))) {
                    out.suggestedMode = "deep";
                    out.suggestedModeTitleRu = getModeTitleRu("deep");
                    out.suggestedModeReasonRu = "⚠ Поле на грани тупика (останется " + finalEmpty
                            + " пуст.) — включите «🔬 Максимальный» (340 лучей) для глубокого поиска спасения!";
                    out.urgent = true;
                    return out;
                }
            }

            // Early warning: 5 empty cells left while board is getting clogged with multi-layer/walled stacks
            boolean earlyCrunch = (finalEmpty == 4 || finalEmpty == 5)
                    && (emptyNow <= 6 || walledCnt >= 2 || multiTowers >= 7 || mergeProb < 44.0);
            if (earlyCrunch && ("fast".equals(curM) || "balanced".equals(curM))) {
                out.suggestedMode = "survival";
                out.suggestedModeTitleRu = getModeTitleRu("survival");
                out.suggestedModeReasonRu = "⚠ Места становится мало (пусто: " + finalEmpty
                        + (walledCnt > 0 ? ", зажато: " + walledCnt : "")
                        + ") — советуем заранее перейти на «🛡 Выживание»!";
                out.urgent = true;
                return out;
            }

            // Late-stage complex endgame (3-layer height-6 hands at >=72% score on 450+ stages)
            if (targetScore >= 450 && progress >= 0.72 && finalEmpty <= 6 && multiTowers >= 5
                    && ("fast".equals(curM) || "balanced".equals(curM))) {
                out.suggestedMode = "deep";
                out.suggestedModeTitleRu = getModeTitleRu("deep");
                out.suggestedModeReasonRu = "🔬 Сложная концовка (" + curScore + "/" + targetScore
                        + ", идут 3-слойные пачки) — советуем «🔬 Максимальный»!";
                out.urgent = true;
                return out;
            }
        }

        // ------------------------------------------------------------------
        // PRIORITY 2: Stage-Specific Optimal Profile on Scan / Stage Start
        // ------------------------------------------------------------------
        if (isFreshStageScan || curScore == 0) {
            String ideal;
            String reason;
            if (stageNo <= 2 || targetScore <= 180) {
                ideal = "fast";
                reason = "💡 Этап " + stageNo + " — короткий блиц без замков (цель " + targetScore
                        + "): оптимален «⚡ Быстрый» для мгновенного расчёта.";
            } else if (stageNo == 13 || stageNo == 16) {
                ideal = "deep";
                reason = "💡 Этап " + stageNo + " (цель " + targetScore
                        + ", стартовые 4-слойные башни + замки): лучше всего подходит «🔬 Максимальный».";
            } else if (stageNo == 9 && activeLocks >= 3) {
                ideal = "survival";
                reason = "💡 Этап 9 (7 замков на поле!): рекомендуем «🛡 Выживание», чтобы не зажать доску до открытия замков.";
            } else if (stageNo == 12 || (totalCells > 0 && totalCells <= 18 && targetScore >= 500)) {
                ideal = "survival";
                reason = "💡 Этап " + stageNo + " (узкое поле из " + totalCells
                        + " клеток при цели " + targetScore + "): советуем «🛡 Выживание».";
            } else if ((stageNo == 8 || stageNo == 15) && (activeLocks >= 2 || multiTowers >= 3)) {
                ideal = "survival";
                reason = "💡 Этап " + stageNo + " (тесный старт: замки и готовые башни): советуем «🛡 Выживание» для расчистки места.";
            } else {
                ideal = "balanced";
                reason = "💡 Этап " + stageNo + " (просторное поле, цель " + targetScore
                        + "): оптимален «⚖️ Оптимальный» для набора комбо и очков.";
            }

            if (!ideal.equals(curM)) {
                // On cramped stages, if user already chose "deep", don't downgrade unless blitz stage 1-2
                if ("survival".equals(ideal) && "deep".equals(curM)) {
                    return out;
                }
                out.suggestedMode = ideal;
                out.suggestedModeTitleRu = getModeTitleRu(ideal);
                out.suggestedModeReasonRu = reason;
                out.urgent = false;
            }
        }
        return out;
    }

    /**
     * Frame-to-frame pixel-delta filter.
     * Computes per-cell MAD between previous and current frames on unclaimed pixels only.
     * Returns a mask of cells where pixel-delta is LOW (likely unchanged → suppress false CV diffs).
     * Does NOT gate CV analysis — only lowers confidence for spurious cell-diff detections.
     */
    public static int[] computeFrameDelta(
            int[] curArgb, int curW, int curH,
            int[] prevArgb, int prevW, int prevH,
            int[] claimed, int cellCount
    ) {
        if (curArgb == null || prevArgb == null || curArgb.length != prevArgb.length) {
            return null;
        }
        if (curW != prevW || curH != prevH) {
            return null;
        }
        int totalPx = curW * curH;
        // Compute per-cell MAD on unclaimed pixels
        int[] cellMad = new int[cellCount + 1];
        int[] cellUnclaimedPx = new int[cellCount + 1];
        for (int i = 0; i < totalPx; i++) {
            int a = curArgb[i] & 0xFFFFFF;
            int b = prevArgb[i] & 0xFFFFFF;
            int dr = ((a >> 16) & 0xFF) - ((b >> 16) & 0xFF);
            int dg = ((a >> 8) & 0xFF) - ((b >> 8) & 0xFF);
            int db = (a & 0xFF) - (b & 0xFF);
            int mad = (Math.abs(dr) + Math.abs(dg) + Math.abs(db)) / 3;
            int cid = claimed[i];
            if (cid >= 1 && cid <= cellCount) {
                cellMad[cid] += mad;
                cellUnclaimedPx[cid]++;
            }
        }
        // Normalize: average MAD per cell
        int[] cellAvgMad = new int[cellCount + 1];
        for (int cid = 1; cid <= cellCount; cid++) {
            if (cellUnclaimedPx[cid] > 0) {
                cellAvgMad[cid] = cellMad[cid] / cellUnclaimedPx[cid];
            } else {
                cellAvgMad[cid] = 0;
            }
        }
        return cellAvgMad;
    }

    /**
     * Calibrate threshold eps from trusted cells (95th percentile × 1.15).
     * Trusted = cells where basic CV reading matches the model and cell is not occluded.
     */
    public static int calibrateFrameDeltaEps(int[] cellAvgMad, boolean[] trustedCells, int cellCount) {
        if (cellAvgMad == null) return 12;
        java.util.List<Integer> trusted = new java.util.ArrayList<>();
        for (int cid = 1; cid <= cellCount; cid++) {
            if (trustedCells != null && cid < trustedCells.length && trustedCells[cid] && cellAvgMad[cid] > 0) {
                trusted.add(cellAvgMad[cid]);
            }
        }
        if (trusted.isEmpty()) return 12;
        java.util.Collections.sort(trusted);
        int p95Idx = (int) (trusted.size() * 0.95);
        if (p95Idx >= trusted.size()) p95Idx = trusted.size() - 1;
        int p95 = trusted.get(p95Idx);
        return Math.max(4, (int) (p95 * 1.15));
    }

    /**
     * Apply frame-delta suppression to cell diffs.
     * Returns true if the cell's diff should be suppressed (low pixel-delta = likely unchanged).
     * Never suppresses occluded cells — pixel-delta is meaningless there.
     */
    public static boolean shouldSuppressByFrameDelta(
            int cellId, int cellMad, int eps,
            boolean occluded
    ) {
        if (occluded) return false;
        if (cellMad <= 0) return false;
        return cellMad < eps;
    }

    private static Map<Integer, StageCfg> sStages;

    public static synchronized Map<Integer, StageCfg> getStages(Context ctx) {
        if (sStages != null) return sStages;
        sStages = new HashMap<>();
        try (InputStream is = ctx.getAssets().open("stages_config.json")) {
            byte[] buf = new byte[is.available()];
            int read = 0;
            while (read < buf.length) {
                int r = is.read(buf, read, buf.length - read);
                if (r <= 0) break;
                read += r;
            }
            JSONObject root = new JSONObject(new String(buf, 0, read, StandardCharsets.UTF_8));
            JSONArray arr = root.getJSONArray("stages");
            for (int i = 0; i < arr.length(); i++) {
                JSONObject sj = arr.getJSONObject(i);
                StageCfg sc = new StageCfg();
                sc.stageNo = sj.getInt("stageNo");
                sc.targetScore = sj.getInt("targetScore");
                sc.boardType = sj.optInt("boardType", 1);
                JSONArray cj = sj.getJSONArray("cells");
                for (int k = 0; k < cj.length(); k++) {
                    JSONObject c = cj.getJSONObject(k);
                    StageCellCfg scc = new StageCellCfg();
                    scc.cellId = c.getInt("cellId");
                    scc.unlockCellScore = c.optInt("unlockCellScore", 0);
                    scc.unlockCardScore = c.optInt("unlockCardScore", 0);
                    JSONArray initArr = c.optJSONArray("initLayers");
                    if (initArr != null) {
                        for (int m = 0; m < initArr.length(); m++) {
                            JSONObject lj = initArr.getJSONObject(m);
                            scc.initLayers.add(new Layer(lj.getInt("colour"), lj.getInt("count")));
                        }
                    }
                    sc.cells.add(scc);
                }
                sStages.put(sc.stageNo, sc);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return sStages;
    }

    public static CellState[] createStageBoard(StageCfg stg, int curScore) {
        CellState[] board = new CellState[MAX_POS_COUNT + 1];
        for (int cid = 1; cid <= MAX_POS_COUNT; cid++) {
            board[cid] = new CellState(cid, 0);
        }
        for (StageCellCfg sc : stg.cells) {
            CellState cs = new CellState(sc.cellId, 1);
            cs.lockScore = sc.unlockCellScore > curScore ? sc.unlockCellScore : 0;
            cs.lockCardScore = sc.unlockCardScore > curScore ? sc.unlockCardScore : 0;
            for (Layer l : sc.initLayers) cs.layers.add(l.copy());
            cs.normalize();
            board[sc.cellId] = cs;
        }
        return board;
    }

    public static CellState[] cloneBoard(CellState[] src) {
        CellState[] dst = new CellState[MAX_POS_COUNT + 1];
        for (int i = 1; i <= MAX_POS_COUNT; i++) {
            dst[i] = src[i] != null ? src[i].copy() : new CellState(i, 0);
        }
        return dst;
    }

    public static double[] cellWorldPos(int cellId, int rotationDeg) {
        int col = (cellId - 1) / MAX_ROW + 1;
        int row = (cellId - 1) % MAX_ROW + 1;
        double x = -213.0 + (col - 1) * 86.0;
        double topZ = (col % 2 == 1) ? 258.0 : 308.0;
        double z = topZ - (row - 1) * 100.0;
        double dx = x - 2.0;
        double dz = z - (-17.0);
        double rad = Math.toRadians(rotationDeg % 360);
        double rx = dx * Math.cos(rad) + dz * Math.sin(rad);
        double rz = -dx * Math.sin(rad) + dz * Math.cos(rad);
        return new double[]{rx, -rz};
    }

    // --- EXACT GAME SIMULATOR (CardMasterBoardSim) ---
    private static int getComboTotal(int cfgId) {
        if (cfgId <= 0) return 0;
        int s = 0;
        for (int k = 1; k <= 6 && k <= cfgId; k++) s += SERIES_DISPEL[k];
        return s;
    }

    private static List<CellState> getSameColorAdj(CellState[] cells, CellState cur, boolean nextColMode) {
        int target = nextColMode ? cur.topNextColor() : cur.topColor();
        if (target <= 0) return Collections.emptyList();
        List<CellState> out = new ArrayList<>(4);
        for (int nid : ADJACENT_IDS[cur.cellId]) {
            CellState nb = cells[nid];
            if (nb != null && nb.exist == 1 && !nb.isLocked() && nb.hasBlocks() && nb.topColor() == target) {
                out.add(nb);
            }
        }
        Collections.sort(out, Comparator.comparingInt(a -> a.cellId));
        return out;
    }

    private static int sumTopCounts(List<CellState> list) {
        int s = 0;
        for (CellState c : list) s += c.topCount();
        return s;
    }

    private static void mergeTopLayer(CellState from, CellState to) {
        if (from.layers.isEmpty() || to.layers.isEmpty()) return;
        Layer ft = from.layers.get(from.layers.size() - 1);
        Layer tt = to.layers.get(to.layers.size() - 1);
        if (ft.colour != tt.colour) return;
        tt.count += ft.count;
        from.layers.remove(from.layers.size() - 1);
    }

    public static class SimResult {
        public boolean valid;
        public CellState[] newCells;
        public int finalScore;
        public int gainedScore;
        public int comboBonusScore;
        public int totalEliminations;
        public int blocksEliminated;
        public List<Integer> unlockedCells = new ArrayList<>();
    }

    public static SimResult simulatePlaceCard(CellState[] board, List<Layer> cardLayers, int targetCid, int curScore) {
        SimResult res = new SimResult();
        CellState[] cells = cloneBoard(board);
        for (int i = 1; i <= MAX_POS_COUNT; i++) {
            CellState c = cells[i];
            if (c.exist != 1) continue;
            if (c.lockScore > 0 && curScore >= c.lockScore) c.lockScore = 0;
            if (c.lockCardScore > 0 && curScore >= c.lockCardScore) c.lockCardScore = 0;
        }
        if (targetCid < 1 || targetCid > MAX_POS_COUNT || cardLayers == null || cardLayers.isEmpty()) {
            res.newCells = cells;
            res.finalScore = curScore;
            return res;
        }
        CellState target = cells[targetCid];
        if (!target.isEmptyPlaceable()) {
            res.newCells = cells;
            res.finalScore = curScore;
            return res;
        }

        for (Layer l : cardLayers) target.layers.add(l.copy());
        target.normalize();

        List<CellState> checkList = new ArrayList<>();
        int[] scoreBox = {curScore};
        int[] baseBox = {0};
        int[] comboBonusBox = {0};
        int[] comboNumBox = {0};
        int[] playedComboBox = {0};
        int[] elimsBox = {0};
        int[] blocksBox = {0};

        pickPerfectIter(cells, target, checkList, scoreBox, baseBox, comboBonusBox, comboNumBox, playedComboBox, elimsBox, blocksBox, res.unlockedCells);

        res.valid = true;
        res.newCells = cells;
        res.finalScore = scoreBox[0];
        res.gainedScore = scoreBox[0] - curScore;
        res.comboBonusScore = comboBonusBox[0];
        res.totalEliminations = elimsBox[0];
        res.blocksEliminated = blocksBox[0];
        return res;
    }

    private static void pickPerfectIter(
            CellState[] cells,
            CellState startCell,
            List<CellState> checkList,
            int[] allScore,
            int[] baseScore,
            int[] comboBonus,
            int[] comboNum,
            int[] playedCombo,
            int[] totalElims,
            int[] blocksElim,
            List<Integer> unlockedAll
    ) {
        CellState cur = startCell;
        int safety = 0;
        while (safety++ < 500) {
            List<CellState> same = getSameColorAdj(cells, cur, false);
            int n = same.size();

            if (n == 0) {
                if (!checkList.isEmpty()) {
                    cur = checkList.remove(0);
                    continue;
                }
                List<CellState> elimList = new ArrayList<>();
                for (int cid = 1; cid <= MAX_POS_COUNT; cid++) {
                    CellState c = cells[cid];
                    if (c.exist == 1 && !c.isLocked() && c.topCount() >= ELIMINATE_NUM) {
                        elimList.add(c);
                    }
                }
                if (elimList.isEmpty()) return;

                int waveBase = 0;
                for (CellState ec : elimList) {
                    int cnt = ec.topCount();
                    ec.layers.remove(ec.layers.size() - 1);
                    waveBase += cnt;
                    blocksElim[0] += cnt;
                    totalElims[0]++;
                    comboNum[0]++;
                    if (!getSameColorAdj(cells, ec, false).isEmpty() && !checkList.contains(ec)) {
                        checkList.add(ec);
                    }
                }
                int waveCombo = 0;
                int realCombo = comboNum[0] - 1;
                if (realCombo >= 1) {
                    if (realCombo <= 6) {
                        int inc = getComboTotal(realCombo) - getComboTotal(playedCombo[0]);
                        if (inc > 0) waveCombo += inc;
                        playedCombo[0] = realCombo;
                    } else {
                        waveCombo += getComboTotal(6);
                        playedCombo[0] = 6;
                    }
                }
                baseScore[0] += waveBase;
                comboBonus[0] += waveCombo;
                allScore[0] += waveBase + waveCombo;

                for (int cid = 1; cid <= MAX_POS_COUNT; cid++) {
                    CellState c = cells[cid];
                    if (c.exist != 1) continue;
                    boolean unl = false;
                    if (c.lockScore > 0 && allScore[0] >= c.lockScore) {
                        c.lockScore = 0;
                        unl = true;
                    }
                    if (c.lockCardScore > 0 && allScore[0] >= c.lockCardScore) {
                        c.lockCardScore = 0;
                        unl = true;
                        if (c.hasBlocks() && !getSameColorAdj(cells, c, false).isEmpty() && !checkList.contains(c)) {
                            checkList.add(c);
                        }
                    }
                    if (unl && !unlockedAll.contains(cid)) unlockedAll.add(cid);
                }

                if (!checkList.isEmpty()) {
                    cur = checkList.remove(0);
                    continue;
                }
                return;
            }

            CellState fromCell, toCell;
            if (n == 1) {
                CellState adj = same.get(0);
                boolean cp = cur.isPure(), ap = adj.isPure();
                if (cp && ap) {
                    int n1 = getSameColorAdj(cells, cur, false).size();
                    int n2 = getSameColorAdj(cells, adj, false).size();
                    if (n2 > n1) { fromCell = cur; toCell = adj; }
                    else { fromCell = adj; toCell = cur; }
                } else if (cp || ap) {
                    fromCell = cp ? adj : cur;
                    toCell = cp ? cur : adj;
                } else {
                    List<CellState> nx1 = getSameColorAdj(cells, cur, true);
                    List<CellState> nx2 = getSameColorAdj(cells, adj, true);
                    if (nx1.size() != nx2.size()) {
                        if (nx1.size() > nx2.size()) { fromCell = cur; toCell = adj; }
                        else { fromCell = adj; toCell = cur; }
                    } else if (nx1.isEmpty()) {
                        fromCell = adj; toCell = cur;
                    } else {
                        if (sumTopCounts(nx1) > sumTopCounts(nx2)) { fromCell = cur; toCell = adj; }
                        else { fromCell = adj; toCell = cur; }
                    }
                }
            } else {
                toCell = cur;
                List<CellState> comb = new ArrayList<>();
                for (CellState sc : same) if (!sc.isPure()) comb.add(sc);
                if (comb.isEmpty()) {
                    fromCell = same.get(0);
                } else if (comb.size() == 1) {
                    fromCell = comb.get(0);
                } else {
                    CellState c1 = comb.get(0), c2 = comb.get(1);
                    List<CellState> nx1 = getSameColorAdj(cells, c1, true);
                    List<CellState> nx2 = getSameColorAdj(cells, c2, true);
                    if (nx1.size() != nx2.size()) {
                        fromCell = nx1.size() > nx2.size() ? c1 : c2;
                    } else if (nx1.isEmpty()) {
                        fromCell = c1;
                    } else {
                        fromCell = sumTopCounts(nx1) > sumTopCounts(nx2) ? c1 : c2;
                    }
                }
            }

            mergeTopLayer(fromCell, toCell);
            checkList.remove(fromCell);
            checkList.remove(toCell);

            if (!getSameColorAdj(cells, toCell, false).isEmpty()) {
                if (fromCell.hasBlocks() && !checkList.contains(fromCell)) checkList.add(fromCell);
                cur = toCell;
            } else {
                if (toCell.hasBlocks() && !checkList.contains(toCell)) checkList.add(toCell);
                cur = fromCell;
            }
        }
    }

    // --- GRANDMASTER EXPECTIMAX + HEX-DISTANCE ISLAND + 2-LAYER PEEL + ROLLOUT SOLVER ---
    public static class Forecast {
        public double mergeProbPct;
        public double burstProbPct;
        public double expectimaxScore;
        public List<Integer> openColors = new ArrayList<>();
        public List<Integer> burstReadyColors = new ArrayList<>();
        public List<String> walledWarnings = new ArrayList<>();
    }

    private static boolean isHexPassableForDist(CellState c, int curScore) {
        if (c == null || c.exist != 1) return false;
        if (!c.isLocked()) return true;
        int need = Math.max(c.lockScore, c.lockCardScore);
        return (need - curScore) <= 25;
    }

    private static int[][] computeActiveHexDist(CellState[] cells, int curScore) {
        int[][] dist = new int[MAX_POS_COUNT + 1][MAX_POS_COUNT + 1];
        for (int i = 1; i <= MAX_POS_COUNT; i++) Arrays.fill(dist[i], 8);
        int[] q = new int[MAX_POS_COUNT + 2];
        for (int src = 1; src <= MAX_POS_COUNT; src++) {
            if (cells[src] == null || cells[src].exist != 1) continue;
            dist[src][src] = 0;
            int head = 0, tail = 0;
            q[tail++] = src;
            while (head < tail) {
                int u = q[head++];
                int du = dist[src][u];
                for (int v : ADJACENT_IDS[u]) {
                    if (isHexPassableForDist(cells[v], curScore) && dist[src][v] == 8) {
                        dist[src][v] = du + 1;
                        q[tail++] = v;
                    }
                }
            }
        }
        return dist;
    }

    public static Forecast computeReceptivity(CellState[] cells) {
        Forecast f = new Forecast();
        List<Integer> emptyIds = new ArrayList<>(24);
        boolean[] isEmpty = new boolean[MAX_POS_COUNT + 1];
        List<CellState> occ = new ArrayList<>(28);

        for (int cid = 1; cid <= MAX_POS_COUNT; cid++) {
            CellState c = cells[cid];
            if (c == null || c.exist != 1) continue;
            if (c.isEmptyPlaceable()) {
                emptyIds.add(cid);
                isEmpty[cid] = true;
            } else if (!c.isLocked() && c.hasBlocks()) {
                occ.add(c);
            }
        }

        boolean[] openCol = new boolean[9];
        boolean[] burstCol = new boolean[9];
        double bridgeBonus = 0.0;
        double hubScore = 0.0;

        for (int ecid : emptyIds) {
            int activeDeg = 0;
            int[] colTot = new int[9];
            int[] colCnt = new int[9];
            int high7Cnt = 0;
            for (int nbId : ADJACENT_IDS[ecid]) {
                CellState nb = cells[nbId];
                if (nb == null || nb.exist != 1 || nb.isLocked()) continue;
                activeDeg++;
                if (!nb.isLocked() && nb.hasBlocks()) {
                    int tc = nb.topColor();
                    int cnt = nb.topCount();
                    openCol[tc] = true;
                    colTot[tc] += cnt;
                    colCnt[tc]++;
                    if (cnt >= 7) high7Cnt++;
                }
            }
            int distinct = 0;
            for (int c = 1; c <= 8; c++) {
                if (colCnt[c] > 0) {
                    distinct++;
                    if (colCnt[c] >= 2) bridgeBonus += 46.0 + colTot[c] * 5.5;
                    if (colTot[c] >= 7) burstCol[c] = true;
                }
            }
            hubScore += activeDeg * 5.5 + distinct * 11.0;
            if (distinct >= 2) hubScore += (distinct - 1) * 9.5;
            if (high7Cnt >= 1 && distinct >= 2) {
                hubScore += high7Cnt * (distinct - 1) * 16.0;
            }
        }

        double breathingHigh = 0.0;
        double walledPen = 0.0;
        double pNext3 = 1.0 - Math.pow(7.0 / 8.0, 3);

        for (CellState c : occ) {
            int topC = c.topColor();
            int topN = c.topCount();
            boolean hasAir = false;
            for (int nbId : ADJACENT_IDS[c.cellId]) {
                if (isEmpty[nbId]) { hasAir = true; break; }
            }
            if (hasAir) {
                if (topN >= 6) {
                    double r = (topN - 5) * (topN - 5) * 14.0;
                    if (c.layers.size() == 1) r *= 1.20;
                    breathingHigh += r * (0.65 + pNext3);
                }
            } else {
                if (topN >= 6) {
                    walledPen += (topN - 4) * (topN - 4) * 17.5;
                    if (topN >= 7) {
                        f.walledWarnings.add("#" + c.cellId + "(" + SHORT_NAMES_RU[topC] + "×" + topN + ")");
                    }
                } else if (c.layers.size() >= 3) {
                    walledPen += 22.0;
                }
            }
        }

        int emptyPairCnt = 0;
        for (int ecid : emptyIds) {
            for (int nbId : ADJACENT_IDS[ecid]) {
                if (nbId > ecid && isEmpty[nbId]) emptyPairCnt++;
            }
        }
        double adjEmptyBonus = Math.min(6, emptyPairCnt) * 14.5;

        int openCnt = 0, burstCnt = 0;
        for (int c = 1; c <= 8; c++) {
            if (openCol[c]) { openCnt++; f.openColors.add(c); }
            if (burstCol[c]) { burstCnt++; f.burstReadyColors.add(c); }
        }
        double pSingle = openCnt * 0.125;
        double pBurstSingle = burstCnt * 0.125;
        double pAnyMerge = emptyIds.isEmpty() ? 0.0 : (1.0 - Math.pow(1.0 - pSingle, 3));
        double pAnyBurst = (emptyIds.isEmpty() || burstCnt == 0) ? 0.0 : (1.0 - Math.pow(1.0 - pBurstSingle, 3));

        f.mergeProbPct = Math.round(pAnyMerge * 1000.0) / 10.0;
        f.burstProbPct = Math.round(pAnyBurst * 1000.0) / 10.0;
        f.expectimaxScore = pSingle * 135.0 + pAnyBurst * 150.0 + hubScore + bridgeBonus + adjEmptyBonus + breathingHigh - walledPen;
        return f;
    }

    private static double evaluateBoard(
            CellState[] cells, int[][] hexDist, int curScore, int initScore, int targetScore,
            int movesMade, int totalCards, int unlockedTot, int comboSum, String mode
    ) {
        boolean isWin = targetScore > 0 && curScore >= targetScore;
        int gained = curScore - initScore;
        int emptyCells = 0, dirty = 0, totalBlocks = 0;
        double nearTen = 0.0, futureAdj = 0.0;
        int[] topCounts = new int[9];
        int[] topBlocksSum = new int[9];
        boolean[] isEmptyMap = new boolean[MAX_POS_COUNT + 1];
        List<CellState> active = new ArrayList<>(28);
        List<CellState> lockedTowers = new ArrayList<>(6);
        int nearestUnlockNeed = Integer.MAX_VALUE;
        boolean nearestIsCardTower = false;

        for (int cid = 1; cid <= MAX_POS_COUNT; cid++) {
            CellState c = cells[cid];
            if (c == null || c.exist != 1) continue;
            if (c.isEmptyPlaceable()) {
                emptyCells++;
                isEmptyMap[cid] = true;
            } else if (c.isLocked()) {
                int need = Math.max(c.lockScore, c.lockCardScore);
                if (need > curScore && need < nearestUnlockNeed) {
                    nearestUnlockNeed = need;
                    nearestIsCardTower = (c.lockCardScore > curScore && c.hasBlocks());
                }
                if (c.lockCardScore > curScore && c.hasBlocks()) {
                    lockedTowers.add(c);
                }
            } else if (c.hasBlocks()) {
                active.add(c);
                int nl = c.layers.size();
                if (nl > 1) dirty += nl - 1;
                for (Layer l : c.layers) totalBlocks += l.count;
                int tc = c.topColor();
                int tn = c.topCount();
                topCounts[tc]++;
                topBlocksSum[tc] += tn;
                int cap = Math.min(tn, ELIMINATE_NUM - 1);
                nearTen += cap * cap * 0.62 * (nl == 1 ? 1.35 : 0.85);
            }
        }

        if (isWin) return 1_000_000.0 + (totalCards - movesMade) * 15_000.0 + gained * 200.0 + emptyCells * 80.0;
        if (emptyCells == 0) return -500_000.0 + gained * 50.0;

        for (CellState c : active) {
            int sec = c.topNextColor();
            if (sec > 0) {
                for (int nbId : ADJACENT_IDS[c.cellId]) {
                    CellState nb = cells[nbId];
                    if (nb != null && nb.exist == 1 && !nb.isLocked() && nb.hasBlocks()) {
                        if (nb.topColor() == sec) futureAdj += 18.0;
                        else if (nb.topNextColor() == sec) futureAdj += 6.5;
                    }
                }
            }
        }

        // Locked Rainbow Tower (lockCardScore > curScore) Synergy, Anti-Walling & Unlock Rush:
        // Stage 8/11/13/15/16+ feature 8-12 chip locked rainbow towers (e.g. #11 [🔒300], #24 [🔒450]).
        // Parking matching top/2nd colors next to an upcoming locked tower triggers explosive instant merges upon unlock,
        // whereas walling in all its neighbors with unrelated colors strangles the board!
        double lockTowerBonus = 0.0;
        for (CellState lt : lockedTowers) {
            int gap = Math.max(1, lt.lockCardScore - curScore);
            double urg = (gap <= 55) ? 1.65 : (gap <= 135 ? 1.10 : (gap <= 240 ? 0.60 : 0.30));
            int lTopC = lt.topColor();
            int lTopN = lt.topCount();
            int lSecC = lt.topNextColor();
            int openOrMatchNbs = 0;
            int totalPassableNbs = 0;
            for (int nbId : ADJACENT_IDS[lt.cellId]) {
                CellState nb = cells[nbId];
                if (nb == null || nb.exist != 1 || nb.isLocked()) continue;
                totalPassableNbs++;
                if (!nb.hasBlocks()) {
                    openOrMatchNbs++;
                } else {
                    int ntc = nb.topColor();
                    int ntn = nb.topCount();
                    if (ntc == lTopC) {
                        openOrMatchNbs++;
                        lockTowerBonus += (38.0 + Math.min(7, ntn) * 7.5) * urg;
                        if (ntn + lTopN >= ELIMINATE_NUM) {
                            lockTowerBonus += 42.0 * urg; // Instant 10+ clear the moment the padlock pops!
                        }
                    } else if (lSecC > 0 && ntc == lSecC) {
                        lockTowerBonus += 16.0 * urg;
                    } else if (nb.topNextColor() == lTopC) {
                        lockTowerBonus += 12.0 * urg;
                    }
                }
            }
            if (totalPassableNbs > 0 && openOrMatchNbs == 0 && gap <= 165) {
                lockTowerBonus -= 68.0 * urg; // Never completely wall off an upcoming locked rainbow tower!
            }
        }
        if (gained > 0 && nearestUnlockNeed < Integer.MAX_VALUE && (nearestUnlockNeed - initScore) <= 95) {
            double rushMult = (nearestIsCardTower ? 1.45 : 1.0) * (emptyCells <= 5 ? 1.35 : 1.0);
            int remGap = Math.max(0, nearestUnlockNeed - curScore);
            lockTowerBonus += gained * (remGap <= 25 ? 36.0 : 20.0) * rushMult;
        }

        boolean survMode = "survival".equals(mode);
        double survMult = survMode ? 1.35 : (emptyCells <= 4 ? 1.30 : (emptyCells <= 6 ? 1.15 : 1.0));

        // Penalize disconnected islands of the same top color (especially when split blocks >= 8..10!)
        boolean[] hasEmptyBridgeForColor = new boolean[9];
        double islandDistPen = 0.0;
        int nAct = active.size();
        for (int i = 0; i < nAct; i++) {
            CellState u = active.get(i);
            int uc = u.topColor();
            if (topCounts[uc] <= 1) continue;
            for (int j = i + 1; j < nAct; j++) {
                CellState v = active.get(j);
                if (v.topColor() != uc) continue;
                int d = (hexDist != null) ? hexDist[u.cellId][v.cellId] : 3;
                if (d == 2) {
                    boolean bridged = false;
                    for (int nbU : ADJACENT_IDS[u.cellId]) {
                        if (!isEmptyMap[nbU]) continue;
                        for (int nbV : ADJACENT_IDS[v.cellId]) {
                            if (nbU == nbV) { bridged = true; break; }
                        }
                        if (bridged) break;
                    }
                    if (bridged) {
                        hasEmptyBridgeForColor[uc] = true;
                    } else {
                        islandDistPen += 22.0;
                    }
                } else if (d >= 3) {
                    islandDistPen += 18.0 + 9.0 * Math.min(3, d - 2);
                }
            }
        }

        double scattered = islandDistPen * survMult;
        for (int c = 1; c <= 8; c++) {
            if (topCounts[c] > 1) {
                scattered += (topCounts[c] - 1) * 42.0 * survMult;
                if (topBlocksSum[c] >= 10) {
                    scattered += (hasEmptyBridgeForColor[c] ? 32.0 : 96.0) * survMult;
                } else if (topBlocksSum[c] >= 8) {
                    scattered += (hasEmptyBridgeForColor[c] ? 12.0 : 44.0) * survMult;
                }
            }
        }

        // Anti-Clogger & Bridge-Wedge Penalty:
        // Avoid clogging central hubs (unlockedDeg >= 3) or wedging shut the bridge between two same-color towers
        // with tiny 1-2 block orphan tails (e.g. #17 Хлеб×1 or #30 Яблоко×1 between #23 Колос×6 and #31 Колос×3).
        double cloggerPen = 0.0;
        int[] nbColCnt = new int[9];
        int[] nbColBlocks = new int[9];
        for (CellState c : active) {
            int tc = c.topColor();
            int tn = c.topCount();
            int unlockedDeg = 0;
            int innerBlockedAdj = 0;
            boolean hasSameColorSupport = false;
            Arrays.fill(nbColCnt, 0);
            Arrays.fill(nbColBlocks, 0);
            for (int nbId : ADJACENT_IDS[c.cellId]) {
                CellState nb = cells[nbId];
                if (nb == null || nb.exist != 1 || nb.isLocked()) {
                    if (nb != null && (nb.isLocked() || ADJACENT_IDS[nbId].length >= 5)) innerBlockedAdj++;
                    continue;
                }
                unlockedDeg++;
                if (nb.hasBlocks()) {
                    int ntc = nb.topColor();
                    nbColCnt[ntc]++;
                    nbColBlocks[ntc] += nb.topCount();
                    if (ntc == tc || nb.topNextColor() == tc) {
                        hasSameColorSupport = true;
                    }
                }
            }
            // Pinch-bridge protection around holes/locks (e.g. #16, #18 on Stage 11): avoid multi-layer garbage on narrow bridges!
            if (innerBlockedAdj >= 2 && unlockedDeg >= 2 && unlockedDeg <= 4 && c.layers.size() > 1) {
                cloggerPen += (c.layers.size() - 1) * (innerBlockedAdj >= 3 ? 28.0 : 16.0);
            }
            if (tn <= 2 && unlockedDeg >= 3) {
                boolean bridgedSame = hasEmptyBridgeForColor[tc];
                if (!hasSameColorSupport && !bridgedSame) {
                    cloggerPen += (tn == 1 ? 24.0 : 10.0) * (unlockedDeg - 2);
                } else if (tn == 1 && unlockedDeg >= 4) {
                    cloggerPen += 9.0 * (unlockedDeg - 3);
                }
            }
            for (int col = 1; col <= 8; col++) {
                if (col != tc && nbColCnt[col] >= 2 && !hasEmptyBridgeForColor[col]) {
                    cloggerPen += 24.0 + (tn <= 2 ? 20.0 : 0.0) + (nbColBlocks[col] >= 7 ? 18.0 : 0.0);
                }
            }
        }
        cloggerPen *= survMult;

        double danger = 0.0;
        if (emptyCells == 1) danger = -2200.0;
        else if (emptyCells == 2) danger = -980.0;
        else if (emptyCells == 3) danger = -420.0;
        else if (emptyCells == 4) danger = -165.0;
        else if (emptyCells == 5) danger = -55.0;
        danger *= survMult;

        Forecast fc = computeReceptivity(cells);
        double expW = (survMode || emptyCells <= 5) ? 1.20 : 1.0;

        return gained * (survMode ? 85.0 : 130.0)
                + comboSum * 30.0
                + unlockedTot * 560.0
                + lockTowerBonus
                + emptyCells * (155.0 * survMult)
                - dirty * 56.0
                - totalBlocks * 1.2
                + nearTen
                + futureAdj
                - scattered
                - cloggerPen
                + danger
                + fc.expectimaxScore * expW;
    }

    // Score-Aware Stratified 16-Hand Rollout Suites calibrated on 42 real Stage-7 hands (126 stacks):
    // - Bracket 0 (Early < 35% score): ~51.5% 1-lay, ~45.5% 2-lay, ~3.0% 3-lay
    // - Bracket 1 (Mid 35%..78% score): ~18.0% 1-lay, ~61.0% 2-lay, ~21.0% 3-lay
    // - Bracket 2 (Late >= 78% score):  ~8.3% 1-lay, ~58.3% 2-lay, ~33.3% 3-lay
    // Enforces: 0% A->B->A sandwiches, stack height up to 6 (~14.3%), ~44% shared-top hands,
    // slot complexity gradient (Slot #1 -> #2 -> #3), and color-symmetric 8-color permutation decks.
    private static final List<List<List<Layer>>> ROLLOUT_EARLY = buildStratifiedRolloutSuite(0);
    private static final List<List<List<Layer>>> ROLLOUT_MID = buildStratifiedRolloutSuite(1);
    private static final List<List<List<Layer>>> ROLLOUT_LATE = buildStratifiedRolloutSuite(2);

    private static int drawSymmetricColor(int[] deck, int[] ptrRef, java.util.Random rng, int forbidA, int forbidB) {
        for (int tries = 0; tries < 16; tries++) {
            if (ptrRef[0] >= 8) {
                for (int i = 7; i > 0; i--) {
                    int j = rng.nextInt(i + 1);
                    int tmp = deck[i]; deck[i] = deck[j]; deck[j] = tmp;
                }
                ptrRef[0] = 0;
            }
            int cand = deck[ptrRef[0]++];
            if (cand != forbidA && cand != forbidB) return cand;
        }
        for (int c = 1; c <= 8; c++) {
            if (c != forbidA && c != forbidB) return c;
        }
        return 1;
    }

    private static List<List<List<Layer>>> buildStratifiedRolloutSuite(int bracket) {
        java.util.Random rng = new java.util.Random(77001L + bracket * 1009L);
        int nHands = 16;
        int[] topDeck = {1, 2, 3, 4, 5, 6, 7, 8};
        int[] subDeck = {1, 2, 3, 4, 5, 6, 7, 8};
        int[] topPtr = {8};
        int[] subPtr = {8};

        // Per-slot layer thresholds [p1Pct, p1Plus2Pct] capturing Slot #1 -> #2 -> #3 complexity gradient
        int[][] slotLayerCdf;
        if (bracket == 0) {
            slotLayerCdf = new int[][]{{58, 98}, {52, 97}, {44, 95}};
        } else if (bracket == 1) {
            slotLayerCdf = new int[][]{{22, 88}, {18, 83}, {14, 71}};
        } else {
            slotLayerCdf = new int[][]{{12, 82}, {8, 72}, {5, 46}};
        }

        List<List<List<Layer>>> hands = new ArrayList<>(nHands);
        for (int h = 0; h < nHands; h++) {
            List<List<Layer>> hand = new ArrayList<>(3);
            boolean forceSharedTop = ((h * 7 + 3) % 16) < 7; // 7/16 = 43.75% shared-top hands
            int sharedSrcSlot = h % 2;
            int sharedDstSlot = (sharedSrcSlot == 0) ? (1 + ((h / 2) % 2)) : 2;
            int[] topColsChosen = new int[3];

            for (int s = 0; s < 3; s++) {
                int rL = rng.nextInt(100);
                int nl = (rL < slotLayerCdf[s][0]) ? 1 : (rL < slotLayerCdf[s][1] ? 2 : 3);

                int topCol;
                if (forceSharedTop && s == sharedDstSlot && topColsChosen[sharedSrcSlot] > 0) {
                    topCol = topColsChosen[sharedSrcSlot];
                } else {
                    int fA = (s >= 1 && !forceSharedTop) ? topColsChosen[0] : -1;
                    int fB = (s >= 2 && !forceSharedTop) ? topColsChosen[1] : -1;
                    topCol = drawSymmetricColor(topDeck, topPtr, rng, fA, fB);
                }
                topColsChosen[s] = topCol;

                // All layers inside one stack have strictly distinct colors (0% A->B->A sandwiches!)
                int[] cols = new int[nl];
                cols[nl - 1] = topCol;
                if (nl >= 2) {
                    cols[nl - 2] = drawSymmetricColor(subDeck, subPtr, rng, topCol, -1);
                }
                if (nl == 3) {
                    cols[0] = drawSymmetricColor(subDeck, subPtr, rng, topCol, cols[1]);
                }

                int[] cnts = new int[nl];
                if (nl == 1) {
                    int rH = rng.nextInt(100);
                    cnts[0] = (rH < 28) ? 2 : (rH < 74 ? 3 : 4);
                } else if (nl == 2) {
                    int rH = rng.nextInt(100);
                    int tot = (bracket == 0)
                            ? ((rH < 42) ? 3 : (rH < 82 ? 4 : 5))
                            : ((rH < 26) ? 3 : (rH < 62 ? 4 : (rH < 85 ? 5 : 6)));
                    cnts[0] = 1;
                    cnts[1] = 1;
                    for (int rem = tot - 2; rem > 0; rem--) {
                        cnts[rng.nextInt(100) < 56 ? 1 : 0]++;
                    }
                } else {
                    // 3-layer wedge distribution calibrated to 1.25 / 1.58 / 1.79 (total 3..6)
                    cnts[0] = (rng.nextInt(100) < 76) ? 1 : 2;
                    cnts[1] = (rng.nextInt(100) < 52) ? 1 : 2;
                    int maxTop = Math.max(1, 6 - cnts[0] - cnts[1]);
                    int rT = rng.nextInt(100);
                    int wantTop = (rT < 36) ? 1 : (rT < 82 ? 2 : 3);
                    cnts[2] = Math.min(maxTop, wantTop);
                }

                List<Layer> card = new ArrayList<>(nl);
                for (int i = 0; i < nl; i++) card.add(new Layer(cols[i], cnts[i]));
                hand.add(card);
            }
            hands.add(hand);
        }
        return hands;
    }

    // ===================== ПРЕДСКАЗАНИЕ СЛЕДУЮЩЕЙ РУКИ =====================
    // Обучено на реальных логах игроков (uploads/прочитано): (этап, рука) → следующая
    // рука детерминирована в ~98% случаев (H(next|current)=0.03 бит). Таблица —
    // HandTransitionsData (генерируется из логов). Прогноз встраивается в look-ahead
    // прокатку, чтобы «программа видела» будущую руку вместо синтетической выборки.
    private static java.util.Map<String, String> handTransStage;
    private static java.util.Map<String, String> handTransGlobal;

    private static void ensureHandTransitionsLoaded() {
        if (handTransStage != null) return;
        handTransStage = new HashMap<>();
        handTransGlobal = new HashMap<>();
        for (String l : HandTransitionsData.STAGE_TABLE.split("\n")) {
            if (l.isEmpty()) continue;
            String[] p = l.split(";", 3);
            if (p.length == 3) handTransStage.put(p[0] + "|" + p[1], p[2]);
        }
        for (String l : HandTransitionsData.GLOBAL_TABLE.split("\n")) {
            if (l.isEmpty()) continue;
            String[] p = l.split(";", 2);
            if (p.length == 2) handTransGlobal.put(p[0], p[1]);
        }
    }

    /** Точное предсказание следующей руки из таблицы, обученной на логах.
     *  @return 3 стопки (списки слоёв) либо null, если перехода в таблице нет. */
    static List<List<Layer>> predictNextHand(int stageNo, List<HandItem> hand) {
        if (hand == null || hand.isEmpty()) return null;
        ensureHandTransitionsLoaded();
        String[] sigs = new String[hand.size()];
        for (int i = 0; i < hand.size(); i++) {
            StringBuilder sb = new StringBuilder();
            for (Layer l : hand.get(i).layers) sb.append(l.colour).append('_').append(l.count).append('|');
            sigs[i] = sb.toString();
        }
        Arrays.sort(sigs);
        String key = String.join(",", sigs);
        String next = handTransStage.get(stageNo + "|" + key);
        if (next == null) next = handTransGlobal.get(key);
        if (next == null) return null;
        List<List<Layer>> out = new ArrayList<>(3);
        for (String slot : next.split(",")) {
            List<Layer> layers = new ArrayList<>();
            for (String pr : slot.split("\\|")) {
                if (pr.isEmpty()) continue;
                int us = pr.indexOf('_');
                if (us <= 0) continue;
                try {
                    layers.add(new Layer(Integer.parseInt(pr.substring(0, us)), Integer.parseInt(pr.substring(us + 1))));
                } catch (NumberFormatException ignore) {}
            }
            if (!layers.isEmpty()) out.add(layers);
        }
        return out.size() == 3 ? out : null;
    }

    /**
     * Шанс пройти этап БЕЗ игровых подсказок (бюджеты бустеров =0): серия
     * детерминированных прокаток с доски после плана. Политика каждой руки —
     * тот же поиск solveTopPlans (режим fast, дёшево), что и в реальной игре;
     * разнообразие линий — случайный выбор 1-го/2-го плана и сдвиг синтетической
     * цепочки рук. Первая рука прокатки — предсказанная таблицей переходов.
     * Вызывается по кнопке «⏩ Шаг+Рука»; никакие подсказки не моделируются.
     */
    public static int estimatePassProbability(SolvePlan plan, List<List<Layer>> currentHand,
            int curScore, int targetScore, int stageNo) {
        if (plan == null || targetScore <= 0) return -1;
        if (plan.isWin || plan.finalScore >= targetScore) return 100;
        if (plan.deadlockStopAll || plan.steps.isEmpty()) return 0;
        CellState[] start = plan.steps.get(plan.steps.size() - 1).boardAfter;
        int sScore = plan.finalScore;
        if (sScore >= targetScore) return 100;
        final int LINES = 6, MAX_HANDS = 35;
        int wins = 0;
        for (int li = 0; li < LINES; li++) {
            // Детерминированный «рандом» на линию: линии играют по-разному,
            // но повторный вызов с теми же данными даёт тот же процент.
            java.util.Random rng = new java.util.Random(777001L + li * 104729L);
            CellState[] cb = cloneBoard(start);
            int cs = sScore;
            List<List<Layer>> hand = plan.predictedNextHand;
            if (hand == null || hand.size() < 3) {
                hand = rolloutNextHand(stageNo, currentHand, 0, cs, targetScore);
            }
            boolean dead = false;
            for (int h = 0; h < MAX_HANDS; h++) {
                if (cs >= targetScore) break;
                Map<Integer, List<Layer>> waits = new HashMap<>();
                for (int i = 0; i < hand.size() && i < 3; i++) waits.put(i + 1, hand.get(i));
                List<SolvePlan> plays = solveTopPlans(cb, waits, cs, targetScore, "fast", 2, stageNo, false);
                if (plays == null || plays.isEmpty()) { dead = true; break; }
                // Небольшой разброс: иногда берём 2-й план — моделируем «не идеально».
                SolvePlan pick = (plays.size() > 1 && rng.nextInt(100) < 35) ? plays.get(1) : plays.get(0);
                if (pick.steps == null || pick.steps.isEmpty()) { dead = true; break; }
                for (StepMove sm : pick.steps) {
                    SimResult sr = simulatePlaceCard(cb, sm.cardLayers, sm.targetCellId, cs);
                    if (!sr.valid) { dead = true; break; }
                    cb = sr.newCells;
                    cs = sr.finalScore;
                }
                if (dead || cs >= targetScore) break;
                hand = rolloutNextHand(stageNo, hand, h + 1 + li * 5, cs, targetScore);
            }
            if (!dead && cs >= targetScore) wins++;
        }
        return (int) Math.round(100.0 * wins / LINES);
    }

    /** Следующая рука прокатки: таблица переходов (логи) → синтетика по прогрессу счёта. */
    private static List<List<Layer>> rolloutNextHand(int stageNo, List<List<Layer>> cur, int round, int score, int targetScore) {
        if (cur != null && !cur.isEmpty()) {
            List<HandItem> items = new ArrayList<>(cur.size());
            for (int i = 0; i < cur.size(); i++) {
                HandItem hi = new HandItem();
                hi.slotId = i + 1;
                hi.layers = cur.get(i);
                items.add(hi);
            }
            List<List<Layer>> nxt = predictNextHand(stageNo, items);
            if (nxt != null) return nxt;
        }
        double prog = targetScore > 0 ? (double) score / targetScore : 0.5;
        List<List<List<Layer>>> suite = prog < 0.35 ? ROLLOUT_EARLY
                : (prog < 0.78 ? ROLLOUT_MID : ROLLOUT_LATE);
        return suite.get(round % suite.size());
    }

    private static double evaluateRolloutLookahead(CellState[] leafCells, int[][] hexDist, double staticEval, int leafScore, int targetScore, String mode, List<List<Layer>> predictedNextHand) {
        int initEmp = 0;
        for (int cid = 1; cid <= MAX_POS_COUNT; cid++) {
            if (leafCells[cid] != null && leafCells[cid].isEmptyPlaceable()) initEmp++;
        }
        if (initEmp == 0) return -500_000.0;
        if (targetScore > 0 && leafScore >= targetScore) return staticEval;

        double prog = (targetScore > 0) ? ((double) leafScore / targetScore) : 0.50;
        List<List<List<Layer>>> suite = (prog < 0.35) ? ROLLOUT_EARLY : (prog < 0.78 ? ROLLOUT_MID : ROLLOUT_LATE);
        // Точное предсказание следующей руки из логов идёт ПЕРЕД синтетической выборкой:
        // если переход известен — прокатка играет реальную будущую руку, а не модельную.
        List<List<List<Layer>>> handsToPlay = new ArrayList<>(suite.size() + 4);
        if (predictedNextHand != null && predictedNextHand.size() == 3) {
            // Реальная следующая рука (таблица переходов, ~98% точность) должна
            // весить больше синтетики: раньше она входила1 раз из17 и взгляд
            // вперёд фактически решал сьют. Играем её4 раза из20.
            for (int pw = 0; pw < 4; pw++) handsToPlay.add(predictedNextHand);
        }
        handsToPlay.addAll(suite);
        int nHands = handsToPlay.size();
        int aliveCnt = 0;
        double rSum = 0.0;

        for (int h = 0; h < nHands; h++) {
            List<List<Layer>> rh = handsToPlay.get(h);
            CellState[] cb = leafCells;
            int cs = leafScore;
            boolean[] used = new boolean[3];
            boolean ok = true;

            for (int st = 0; st < 3; st++) {
                if (targetScore > 0 && cs >= targetScore) break;
                int bSi = -1;
                SimResult bSim = null;
                double bVal = -1e18;

                for (int si = 0; si < 3; si++) {
                    if (used[si]) continue;
                    List<Layer> card = rh.get(si);
                    for (int cid = 1; cid <= MAX_POS_COUNT; cid++) {
                        if (!cb[cid].isEmptyPlaceable()) continue;
                        SimResult sm = simulatePlaceCard(cb, card, cid, cs);
                        if (!sm.valid) continue;
                        double v = evaluateBoard(sm.newCells, hexDist, sm.finalScore, cs, targetScore, st + 1, 3, sm.unlockedCells.size(), sm.comboBonusScore, mode);
                        if (v > bVal) {
                            bVal = v;
                            bSi = si;
                            bSim = sm;
                        }
                    }
                }
                if (bSim == null) {
                    ok = false;
                    break;
                }
                used[bSi] = true;
                cb = bSim.newCells;
                cs = bSim.finalScore;
            }

            boolean won = targetScore > 0 && cs >= targetScore;
            int empEnd = 0;
            for (int cid = 1; cid <= MAX_POS_COUNT; cid++) {
                if (cb[cid].isEmptyPlaceable()) empEnd++;
            }
            if (won || (ok && empEnd > 0)) {
                aliveCnt++;
                if (won) {
                    rSum += 2200.0;
                } else {
                    double evEnd = evaluateBoard(cb, hexDist, cs, leafScore, targetScore, 3, 3, 0, 0, mode) * 0.42;
                    rSum += Math.max(-900.0, Math.min(2800.0, evEnd));
                }
            } else {
                rSum -= 1200.0;
            }
        }

        return staticEval * 0.48 + (rSum / nHands) + ((double) aliveCnt / nHands) * 1400.0;
    }

    private static String boardSig(CellState[] cells, int score) {
        StringBuilder sb = new StringBuilder(192);
        sb.append(score).append(':');
        for (int cid = 1; cid <= MAX_POS_COUNT; cid++) {
            CellState c = cells[cid];
            if (c.exist != 1) continue;
            sb.append(cid).append(',').append(c.lockScore).append(',').append(c.lockCardScore).append(',');
            for (Layer l : c.layers) sb.append(l.colour).append('_').append(l.count).append('.');
            sb.append(';');
        }
        return sb.toString();
    }

    private static class HandItem {
        int slotId;
        List<Layer> layers;
        String sig;
    }

    private static class Node {
        CellState[] cells;
        int score;
        int[] rem;
        int[][] moves; // [step][0=handIdx, 1=targetCid]
        int unlocked;
        int comboSum;
        boolean isWin;
        boolean isDead;
        double eval;
    }

    public static SolvePlan solveBestPlan(CellState[] baseBoard, Map<Integer, List<Layer>> waits, int curScore, int targetScore, String mode) {
        return solveBestPlan(baseBoard, waits, curScore, targetScore, mode, 7, false);
    }

    public static SolvePlan solveBestPlan(
            CellState[] baseBoard, Map<Integer, List<Layer>> waits, int curScore, int targetScore,
            String mode, int stageNo, boolean isFreshStageScan
    ) {
        List<SolvePlan> plans = solveTopPlans(baseBoard, waits, curScore, targetScore, mode, 1, stageNo, isFreshStageScan);
        return (plans == null || plans.isEmpty()) ? null : plans.get(0);
    }

    public static List<SolvePlan> solveTopPlans(CellState[] baseBoard, Map<Integer, List<Layer>> waits, int curScore, int targetScore, String mode, int topK) {
        return solveTopPlans(baseBoard, waits, curScore, targetScore, mode, topK, 7, false);
    }

    public static List<SolvePlan> solveTopPlans(
            CellState[] baseBoard, Map<Integer, List<Layer>> waits, int curScore, int targetScore,
            String mode, int topK, int stageNo, boolean isFreshStageScan
    ) {
        CellState[] rootCells = cloneBoard(baseBoard);
        int initialEmpty = 0;
        for (int i = 1; i <= MAX_POS_COUNT; i++) {
            CellState c = rootCells[i];
            if (c.exist != 1) continue;
            if (c.lockScore > 0 && curScore >= c.lockScore) c.lockScore = 0;
            if (c.lockCardScore > 0 && curScore >= c.lockCardScore) c.lockCardScore = 0;
            if (c.isEmptyPlaceable()) initialEmpty++;
        }

        List<HandItem> validHand = new ArrayList<>(3);
        for (int sid = 1; sid <= 3; sid++) {
            List<Layer> raw = waits.get(sid);
            if (raw == null || raw.isEmpty()) continue;
            CellState tmp = new CellState(0, 1);
            for (Layer l : raw) tmp.layers.add(l.copy());
            tmp.normalize();
            if (!tmp.layers.isEmpty()) {
                HandItem hi = new HandItem();
                hi.slotId = sid;
                hi.layers = tmp.layers;
                StringBuilder sg = new StringBuilder();
                for (Layer l : tmp.layers) sg.append(l.colour).append('_').append(l.count).append('|');
                hi.sig = sg.toString();
                validHand.add(hi);
            }
        }

        if (validHand.isEmpty()) return Collections.emptyList();

        int[][] hexDist = computeActiveHexDist(rootCells, curScore);
        int totalCards = validHand.size();
        int beamWidth;
        int rolloutLimit;
        if ("fast".equals(mode)) {
            beamWidth = 55;
            // Прогноз следующей руки должен работать во всех режимах: лёгкая
            // прокатка по3 финалиста — дёшево (~30 мс), но учитывает предсказание.
            rolloutLimit = 3;
        } else if ("survival".equals(mode)) {
            beamWidth = 220;
            rolloutLimit = 18;
        } else if ("deep".equals(mode)) {
            beamWidth = 340;
            rolloutLimit = 24;
        } else {
            // Auto-widen search & rollout when board becomes tight (<= 6 empty cells) even in balanced mode
            beamWidth = (initialEmpty <= 6) ? 220 : 160;
            rolloutLimit = (initialEmpty <= 6) ? 18 : 14;
        }

        Node root = new Node();
        root.cells = rootCells;
        root.score = curScore;
        root.rem = new int[totalCards];
        for (int i = 0; i < totalCards; i++) root.rem[i] = i;
        root.moves = new int[0][];
        root.isWin = targetScore > 0 && curScore >= targetScore;

        List<Node> beam = new ArrayList<>();
        beam.add(root);
        List<Node> finished = new ArrayList<>();

        for (int step = 0; step < totalCards; step++) {
            Map<String, Node> seen = new HashMap<>();
            for (Node node : beam) {
                if (node.isWin || node.isDead || node.rem.length == 0) {
                    finished.add(node);
                    continue;
                }
                List<Integer> placeable = new ArrayList<>();
                for (int cid = 1; cid <= MAX_POS_COUNT; cid++) {
                    if (node.cells[cid].isEmptyPlaceable()) placeable.add(cid);
                }
                if (placeable.isEmpty()) {
                    node.isDead = true;
                    node.eval = evaluateBoard(node.cells, hexDist, node.score, curScore, targetScore, node.moves.length, totalCards, node.unlocked, node.comboSum, mode);
                    finished.add(node);
                    continue;
                }

                Set<String> usedSigs = new HashSet<>();
                for (int hp : node.rem) {
                    HandItem card = validHand.get(hp);
                    if (!usedSigs.add(card.sig)) continue;

                    int[] nextRem = new int[node.rem.length - 1];
                    int ptr = 0;
                    for (int x : node.rem) if (x != hp) nextRem[ptr++] = x;

                    for (int tcid : placeable) {
                        SimResult sim = simulatePlaceCard(node.cells, card.layers, tcid, node.score);
                        if (!sim.valid) continue;

                        int[][] nm = Arrays.copyOf(node.moves, node.moves.length + 1);
                        nm[node.moves.length] = new int[]{hp, tcid};
                        int unl = node.unlocked + sim.unlockedCells.size();
                        int cmb = node.comboSum + sim.comboBonusScore;
                        boolean won = targetScore > 0 && sim.finalScore >= targetScore;

                        double ev = evaluateBoard(sim.newCells, hexDist, sim.finalScore, curScore, targetScore, nm.length, totalCards, unl, cmb, mode);

                        Node child = new Node();
                        child.cells = sim.newCells;
                        child.score = sim.finalScore;
                        child.rem = nextRem;
                        child.moves = nm;
                        child.unlocked = unl;
                        child.comboSum = cmb;
                        child.isWin = won;
                        child.eval = ev;

                        if (won || nextRem.length == 0) {
                            finished.add(child);
                        } else {
                            String[] rs = new String[nextRem.length];
                            for (int i = 0; i < nextRem.length; i++) rs[i] = validHand.get(nextRem[i]).sig;
                            Arrays.sort(rs);
                            String sig = Arrays.toString(rs) + "||" + boardSig(sim.newCells, sim.finalScore);
                            Node prev = seen.get(sig);
                            if (prev == null || child.eval > prev.eval) seen.put(sig, child);
                        }
                    }
                }
            }
            if (!seen.isEmpty()) {
                List<Node> next = new ArrayList<>(seen.values());
                Collections.sort(next, (a, b) -> Double.compare(b.eval, a.eval));
                beam = next.size() > beamWidth ? next.subList(0, beamWidth) : next;
            } else {
                beam = Collections.emptyList();
                break;
            }
        }
        finished.addAll(beam);
        if (finished.isEmpty()) return Collections.emptyList();

        // Deduplicate leaf boards and run 14-hand 1-ply Monte-Carlo Rollout across top 12 finalists
        Map<String, Node> uniqueFinalists = new HashMap<>();
        for (Node n : finished) {
            if (n.moves.length == 0) continue;
            String bsig = n.isWin ? ("WIN:" + n.moves.length + ":" + n.score + ":" + n.moves[0][1]) : boardSig(n.cells, n.score);
            Node prev = uniqueFinalists.get(bsig);
            if (prev == null || n.eval > prev.eval) uniqueFinalists.put(bsig, n);
        }
        List<Node> finalists = new ArrayList<>(uniqueFinalists.values());
        if (finalists.isEmpty()) return Collections.emptyList();
        Collections.sort(finalists, (a, b) -> Double.compare(b.eval, a.eval));

        // Точное предсказание следующей руки (таблица из логов) для look-ahead прокатки
        List<List<Layer>> predNextHand = predictNextHand(stageNo, validHand);
        // Сверка прогноза: при СМЕНЕ руки сравниваем прошлое предсказание с новой рукой
        synchronized (SolverEngine.class) {
            String curSig = slotsSig(handToSlots(validHand));
            boolean handChanged = (sPrevHandSig != null && curSig != null && !sPrevHandSig.equals(curSig));
            if (handChanged) {
                sPredCheck = (sNextHandPredicted != null)
                        ? matchCount(sNextHandPredicted, handToSlots(validHand)) : -1;
            } else if (sNextHandPredicted == null) {
                sPredCheck = -1;
            }
            sNextHandPredicted = predNextHand;
            if (curSig != null) sPrevHandSig = curSig;
        }

        if (!finalists.get(0).isWin && rolloutLimit > 0) {
            List<Node> rolloutPool = new ArrayList<>(rolloutLimit);
            List<Node> remainingPool = new ArrayList<>(finalists.size());
            for (Node fn : finalists) {
                if (rolloutPool.size() < rolloutLimit && fn.moves.length >= totalCards && !fn.isDead) {
                    int emp = 0;
                    for (int cid = 1; cid <= MAX_POS_COUNT; cid++) {
                        if (fn.cells[cid].isEmptyPlaceable()) emp++;
                    }
                    if (emp > 0) {
                        rolloutPool.add(fn);
                        continue;
                    }
                }
                remainingPool.add(fn);
            }
            if (rolloutPool.size() >= 2) {
                for (Node fn : rolloutPool) {
                    fn.eval = evaluateRolloutLookahead(fn.cells, hexDist, fn.eval, fn.score, targetScore, mode, predNextHand);
                }
                // CRITICAL: Re-rank rolloutPool strictly among rollout-evaluated candidates so unscaled rank-#15+ nodes never hijack #1!
                Collections.sort(rolloutPool, (a, b) -> Double.compare(b.eval, a.eval));
                finalists.clear();
                finalists.addAll(rolloutPool);
                finalists.addAll(remainingPool);
            }
        }

        int outCount = Math.min(Math.max(1, topK), finalists.size());
        List<SolvePlan> outPlans = new ArrayList<>(outCount);
        for (int rankIdx = 0; rankIdx < outCount; rankIdx++) {
            Node best = finalists.get(rankIdx);
            SolvePlan plan = new SolvePlan();
            plan.rank = rankIdx + 1;
            plan.predictedNextHand = predNextHand;
            if (predNextHand != null && predNextHand.size() == 3) {
                plan.predictedHandText = formatLayersPlain(predNextHand.get(0)) + " · "
                        + formatLayersPlain(predNextHand.get(1)) + " · " + formatLayersPlain(predNextHand.get(2));
            }
            plan.evalScore = Math.round(best.eval * 100.0) / 100.0;
            plan.isWin = best.isWin;
            plan.totalComboBonus = best.comboSum;

            CellState[] replay = cloneBoard(rootCells);
            int rScore = curScore;
            for (int i = 0; i < best.moves.length; i++) {
                int hp = best.moves[i][0];
                int tcid = best.moves[i][1];
                HandItem card = validHand.get(hp);
                SimResult sim = simulatePlaceCard(replay, card.layers, tcid, rScore);
                replay = sim.newCells;
                rScore = sim.finalScore;
                int emp = 0;
                for (int cid = 1; cid <= MAX_POS_COUNT; cid++) if (replay[cid].isEmptyPlaceable()) emp++;

                StepMove sm = new StepMove();
                sm.stepNum = i + 1;
                sm.slotId = card.slotId;
                sm.targetCellId = tcid;
                sm.cardLayers = new ArrayList<>();
                for (Layer l : card.layers) sm.cardLayers.add(l.copy());
                sm.gainedScore = sim.gainedScore;
                sm.scoreAfter = sim.finalScore;
                sm.emptyAfter = emp;
                sm.unlockedCells = sim.unlockedCells;
                sm.boardAfter = cloneBoard(replay);
                plan.steps.add(sm);
            }

            int finalEmpty = 0;
            for (int cid = 1; cid <= MAX_POS_COUNT; cid++) if (replay[cid].isEmptyPlaceable()) finalEmpty++;
            Forecast fc = computeReceptivity(replay);

            plan.finalScore = rScore;
            plan.totalGainedScore = rScore - curScore;
            plan.finalEmptyCells = finalEmpty;
            plan.mergeProbNextHand = fc.mergeProbPct;
            plan.burstProbNextHand = fc.burstProbPct;
            plan.openColors = fc.openColors;
            plan.burstReadyColors = fc.burstReadyColors;
            plan.walledWarnings = fc.walledWarnings;

            if (!plan.isWin) {
                // Smart Hammer (🔨 Молоток) Target Advisor when board is critical (<= 2 empty cells or deadlock)
                if (rankIdx == 0 && (finalEmpty <= 2 || best.moves.length < totalCards || initialEmpty <= 2)) {
                    populateBestHammerAdvice(plan, rootCells, validHand, curScore, targetScore, hexDist);
                }

                if (finalEmpty == 0 || best.moves.length < totalCards) {
                    plan.recommendReroll = true;
                    List<StepMove> safePrefix = (totalCards >= 2)
                            ? findBestSafeClearingPrefix(rootCells, validHand, curScore, targetScore, hexDist, initialEmpty, mode)
                            : Collections.emptyList();
                    if (!safePrefix.isEmpty()) {
                        StepMove lastSafe = safePrefix.get(safePrefix.size() - 1);
                        Forecast pfc = computeReceptivity(lastSafe.boardAfter);
                        plan.steps.clear();
                        plan.steps.addAll(safePrefix);
                        plan.partialBeforeReroll = true;
                        plan.deadlockStopAll = false;
                        plan.finalScore = lastSafe.scoreAfter;
                        plan.totalGainedScore = lastSafe.scoreAfter - curScore;
                        plan.finalEmptyCells = lastSafe.emptyAfter;
                        plan.mergeProbNextHand = pfc.mergeProbPct;
                        plan.burstProbNextHand = pfc.burstProbPct;
                        plan.openColors = pfc.openColors;
                        plan.burstReadyColors = pfc.burstReadyColors;
                        plan.walledWarnings = pfc.walledWarnings;
                        String stepSpan = (safePrefix.size() == 1) ? "Шаг 1" : ("Шаги 1–" + safePrefix.size());
                        plan.rerollReason = "💡 Сделайте ТОЛЬКО " + stepSpan + " (+" + plan.totalGainedScore
                                + " очк., пустых станет " + lastSafe.emptyAfter
                                + "), СТОП — остаток руки НЕ СТАВЬТЕ, жмите 🔄 и «✋ Рука»!";
                    } else {
                        plan.steps.clear();
                        plan.partialBeforeReroll = false;
                        plan.deadlockStopAll = true;
                        plan.finalScore = curScore;
                        plan.totalGainedScore = 0;
                        plan.finalEmptyCells = 0;
                        String hHint = plan.hammerCellId > 0 ? (" или разбейте 🔨 #" + plan.hammerCellId) : "";
                        plan.rerollReason = "🛑 СТОП! НЕ СТАВЬТЕ ПАЧКИ — ход забьёт поле в 0! Нажмите 🔄 Перемешать" + hHint + "!";
                    }
                } else if (finalEmpty == 1 && plan.totalGainedScore == 0 && initialEmpty >= 3) {
                    plan.recommendReroll = true;
                    plan.rerollReason = "⚠️ ОПАСНО! Ни одного взрыва, останется 1 пустая клетка. Если доступен бустер 🔄 — обновите пачки!";
                } else if (finalEmpty <= 2 && plan.totalGainedScore == 0 && fc.burstProbPct < 24.0 && initialEmpty >= 4) {
                    plan.recommendReroll = true;
                    plan.rerollReason = "⚠️ РИСК ТУПИКА! 0 взрывов и мало места (" + finalEmpty + " пуст.). При наличии 🔄 лучше сменить руку!";
                }
            }
            ModeAdvice ma = computeSolverModeAdvice(stageNo, rootCells, curScore, targetScore, mode, plan, isFreshStageScan);
            plan.suggestedMode = ma.suggestedMode;
            plan.suggestedModeTitleRu = ma.suggestedModeTitleRu;
            plan.suggestedModeReasonRu = ma.suggestedModeReasonRu;
            plan.suggestedModeUrgent = ma.urgent;
            outPlans.add(plan);
        }
        return outPlans;
    }

    /**
     * When playing ALL cards in hand leads to 0 empty cells (deadlock), checks whether playing ONLY
     * 1 (or 2) clearing card(s) first triggers an elimination that keeps/increases empty cells (>= max(1, initialEmpty))
     * BEFORE the player uses 🔄 Перемешать on the remaining card(s).
     */
    private static List<StepMove> findBestSafeClearingPrefix(
            CellState[] rootCells, List<HandItem> validHand, int curScore, int targetScore,
            int[][] hexDist, int initialEmpty, String mode
    ) {
        int nCards = validHand.size();
        if (nCards < 2) return Collections.emptyList();
        int minSafeEmp = Math.max(1, initialEmpty);
        double bestScore = -1e18;
        List<StepMove> bestSteps = Collections.emptyList();

        // Check 1-card clearing moves first
        for (int i = 0; i < nCards; i++) {
            HandItem c1 = validHand.get(i);
            for (int cid1 = 1; cid1 <= MAX_POS_COUNT; cid1++) {
                if (!rootCells[cid1].isEmptyPlaceable()) continue;
                SimResult s1 = simulatePlaceCard(rootCells, c1.layers, cid1, curScore);
                if (!s1.valid) continue;
                int emp1 = 0;
                for (int c = 1; c <= MAX_POS_COUNT; c++) if (s1.newCells[c].isEmptyPlaceable()) emp1++;
                if (s1.gainedScore > 0 && emp1 >= minSafeEmp) {
                    double ev1 = evaluateBoard(s1.newCells, hexDist, s1.finalScore, curScore, targetScore, 1, 1,
                            s1.unlockedCells.size(), s1.comboBonusScore, mode) + emp1 * 320.0 + s1.gainedScore * 35.0;
                    if (ev1 > bestScore) {
                        bestScore = ev1;
                        StepMove sm1 = new StepMove();
                        sm1.stepNum = 1;
                        sm1.slotId = c1.slotId;
                        sm1.targetCellId = cid1;
                        sm1.cardLayers = new ArrayList<>();
                        for (Layer l : c1.layers) sm1.cardLayers.add(l.copy());
                        sm1.gainedScore = s1.gainedScore;
                        sm1.scoreAfter = s1.finalScore;
                        sm1.emptyAfter = emp1;
                        sm1.unlockedCells = s1.unlockedCells;
                        sm1.boardAfter = cloneBoard(s1.newCells);
                        bestSteps = Collections.singletonList(sm1);
                    }
                }
                // Also check 2-card clearing prefixes when hand has 3 cards and Step 2 itself triggers a clear
                if (nCards >= 3 && emp1 >= 1) {
                    for (int j = 0; j < nCards; j++) {
                        if (j == i) continue;
                        HandItem c2 = validHand.get(j);
                        for (int cid2 = 1; cid2 <= MAX_POS_COUNT; cid2++) {
                            if (!s1.newCells[cid2].isEmptyPlaceable()) continue;
                            SimResult s2 = simulatePlaceCard(s1.newCells, c2.layers, cid2, s1.finalScore);
                            if (!s2.valid || s2.gainedScore <= 0) continue;
                            int emp2 = 0;
                            for (int c = 1; c <= MAX_POS_COUNT; c++) if (s2.newCells[c].isEmptyPlaceable()) emp2++;
                            if (emp2 >= minSafeEmp && emp2 >= emp1) {
                                int totGain = s1.gainedScore + s2.gainedScore;
                                double ev2 = evaluateBoard(s2.newCells, hexDist, s2.finalScore, curScore, targetScore, 2, 2,
                                        s1.unlockedCells.size() + s2.unlockedCells.size(),
                                        s1.comboBonusScore + s2.comboBonusScore, mode) + emp2 * 320.0 + totGain * 35.0 - 40.0;
                                if (ev2 > bestScore) {
                                    bestScore = ev2;
                                    StepMove sm1 = new StepMove();
                                    sm1.stepNum = 1;
                                    sm1.slotId = c1.slotId;
                                    sm1.targetCellId = cid1;
                                    sm1.cardLayers = new ArrayList<>();
                                    for (Layer l : c1.layers) sm1.cardLayers.add(l.copy());
                                    sm1.gainedScore = s1.gainedScore;
                                    sm1.scoreAfter = s1.finalScore;
                                    sm1.emptyAfter = emp1;
                                    sm1.unlockedCells = s1.unlockedCells;
                                    sm1.boardAfter = cloneBoard(s1.newCells);

                                    StepMove sm2 = new StepMove();
                                    sm2.stepNum = 2;
                                    sm2.slotId = c2.slotId;
                                    sm2.targetCellId = cid2;
                                    sm2.cardLayers = new ArrayList<>();
                                    for (Layer l : c2.layers) sm2.cardLayers.add(l.copy());
                                    sm2.gainedScore = s2.gainedScore;
                                    sm2.scoreAfter = s2.finalScore;
                                    sm2.emptyAfter = emp2;
                                    sm2.unlockedCells = s2.unlockedCells;
                                    sm2.boardAfter = cloneBoard(s2.newCells);

                                    List<StepMove> pair = new ArrayList<>(2);
                                    pair.add(sm1);
                                    pair.add(sm2);
                                    bestSteps = pair;
                                }
                            }
                        }
                    }
                }
            }
        }
        return bestSteps;
    }

    private static Node runFastHammerBeam(
            CellState[] startCells, List<HandItem> validHand, int curScore, int targetScore, int[][] hexDist, int beamWidth
    ) {
        int totalCards = validHand.size();
        Node root = new Node();
        root.cells = startCells;
        root.score = curScore;
        root.rem = new int[totalCards];
        for (int i = 0; i < totalCards; i++) root.rem[i] = i;
        root.moves = new int[0][];
        root.isWin = targetScore > 0 && curScore >= targetScore;

        List<Node> beam = new ArrayList<>();
        beam.add(root);
        List<Node> finished = new ArrayList<>();

        for (int step = 0; step < totalCards; step++) {
            Map<String, Node> seen = new HashMap<>();
            for (Node node : beam) {
                if (node.isWin || node.isDead || node.rem.length == 0) {
                    finished.add(node);
                    continue;
                }
                List<Integer> placeable = new ArrayList<>();
                for (int cid = 1; cid <= MAX_POS_COUNT; cid++) {
                    if (node.cells[cid].isEmptyPlaceable()) placeable.add(cid);
                }
                if (placeable.isEmpty()) {
                    node.isDead = true;
                    node.eval = -500_000.0;
                    finished.add(node);
                    continue;
                }
                Set<String> usedSigs = new HashSet<>();
                for (int hp : node.rem) {
                    HandItem card = validHand.get(hp);
                    if (!usedSigs.add(card.sig)) continue;
                    int[] nextRem = new int[node.rem.length - 1];
                    int ptr = 0;
                    for (int x : node.rem) if (x != hp) nextRem[ptr++] = x;
                    for (int tcid : placeable) {
                        SimResult sim = simulatePlaceCard(node.cells, card.layers, tcid, node.score);
                        if (!sim.valid) continue;
                        int[][] nm = Arrays.copyOf(node.moves, node.moves.length + 1);
                        nm[node.moves.length] = new int[]{hp, tcid};
                        int unl = node.unlocked + sim.unlockedCells.size();
                        int cmb = node.comboSum + sim.comboBonusScore;
                        boolean won = targetScore > 0 && sim.finalScore >= targetScore;
                        double ev = evaluateBoard(sim.newCells, hexDist, sim.finalScore, curScore, targetScore, nm.length, totalCards, unl, cmb, "survival");

                        Node child = new Node();
                        child.cells = sim.newCells;
                        child.score = sim.finalScore;
                        child.rem = nextRem;
                        child.moves = nm;
                        child.unlocked = unl;
                        child.comboSum = cmb;
                        child.isWin = won;
                        child.eval = ev;
                        if (won || nextRem.length == 0) {
                            finished.add(child);
                        } else {
                            String sig = nm.length + "||" + boardSig(sim.newCells, sim.finalScore);
                            Node prev = seen.get(sig);
                            if (prev == null || child.eval > prev.eval) seen.put(sig, child);
                        }
                    }
                }
            }
            if (!seen.isEmpty()) {
                List<Node> next = new ArrayList<>(seen.values());
                Collections.sort(next, (a, b) -> Double.compare(b.eval, a.eval));
                beam = next.size() > beamWidth ? next.subList(0, beamWidth) : next;
            } else {
                beam = Collections.emptyList();
                break;
            }
        }
        finished.addAll(beam);
        Node best = null;
        for (Node n : finished) {
            if (n.moves.length == 0) continue;
            if (best == null || n.eval > best.eval) best = n;
        }
        return best;
    }

    private static void populateBestHammerAdvice(
            SolvePlan plan, CellState[] rootCells, List<HandItem> validHand, int curScore, int targetScore, int[][] hexDist
    ) {
        int totalCards = validHand.size();
        int bestCid = 0;
        double bestUtility = -1e18;
        int bestEmp = 0;
        int bestGain = 0;

        for (int cid = 1; cid <= MAX_POS_COUNT; cid++) {
            CellState orig = rootCells[cid];
            if (orig == null || orig.exist != 1 || orig.isLocked() || !orig.hasBlocks()) continue;

            int smashedBlocks = 0;
            for (Layer l : orig.layers) smashedBlocks += l.count;

            CellState[] smashed = cloneBoard(rootCells);
            smashed[cid].layers.clear();

            Node cand = runFastHammerBeam(smashed, validHand, curScore, targetScore, hexDist, 40);
            if (cand == null) continue;
            if (!cand.isWin && cand.moves.length < totalCards) continue;

            int emp = 0;
            for (int i = 1; i <= MAX_POS_COUNT; i++) {
                if (cand.cells[i].isEmptyPlaceable()) emp++;
            }
            if (!cand.isWin && emp <= 0) continue;

            int gain = cand.score - curScore;
            // Only recommend Hammer if it genuinely improves empty space by >= +2 (or rescues a 0-empty deadlock / wins)
            boolean rescuesDeadlock = (plan.finalEmptyCells == 0 && emp >= 1);
            boolean meaningfullyBetter = cand.isWin || rescuesDeadlock || (emp >= plan.finalEmptyCells + 2)
                    || (emp >= plan.finalEmptyCells + 1 && gain >= plan.totalGainedScore + 10);
            if (!meaningfullyBetter) continue;

            // Prefer smashing cheap 1-2 block plugs or dirty stacks rather than near-10 pure stacks
            double targetBias = 0.0;
            if (smashedBlocks <= 2) targetBias += 85.0;
            else if (orig.layers.size() >= 2) targetBias += 35.0;
            if (orig.layers.size() == 1 && orig.topCount() >= 7) targetBias -= 130.0;

            double util = (cand.isWin ? 500_000.0 : 0.0) + emp * 520.0 + gain * 38.0 + cand.eval * 0.14 + targetBias;
            if (util > bestUtility) {
                bestUtility = util;
                bestCid = cid;
                bestEmp = emp;
                bestGain = gain;
            }
        }

        // ГЕЙТ: совет по🔨 только при ВКЛ «учитывать подсказки» и молотке в наличии (0 = нет)
        if (bestCid > 0 && boosterTipsAllowed(AppState.get() != null ? AppState.get().boostHammer : -1)) {
            CellState st = rootCells[bestCid];
            String topDesc = SHORT_NAMES_RU[st.topColor()] + "×" + st.topCount() + (st.layers.size() > 1 ? "+" : "");
            plan.hammerCellId = bestCid;
            String gainTxt = bestGain > 0 ? ("комбо +" + bestGain + " очк., ") : "";
            plan.hammerReasonRu = "🔨 Молоток по #" + bestCid + " (" + topDesc + ") → "
                    + gainTxt + "останется " + bestEmp + " пуст.! (после 🔨 нажмите ? Сверить)";
        }
    }

    public static JSONObject serializeBoardCellsJson(CellState[] board) throws Exception {
        JSONObject cellsJson = new JSONObject();
        for (int cid = 1; cid <= MAX_POS_COUNT; cid++) {
            CellState cs = board[cid];
            if (cs == null || cs.exist != 1) continue;
            JSONObject co = new JSONObject();
            co.put("cellId", cid);
            co.put("exist", 1);
            co.put("lockScore", cs.lockScore);
            co.put("lockCardScore", cs.lockCardScore);
            JSONArray la = new JSONArray();
            for (Layer l : cs.layers) {
                JSONObject lo = new JSONObject();
                lo.put("colour", l.colour);
                lo.put("count", l.count);
                la.put(lo);
            }
            co.put("layers", la);
            cellsJson.put(String.valueOf(cid), co);
        }
        return cellsJson;
    }

    public static String solvePlansToJson(
            CellState[] baseBoard, Map<Integer, List<Layer>> waits, int curScore, int targetScore,
            String mode, int topK, int stageNo, boolean isFreshStageScan
    ) {
        try {
            List<SolvePlan> plans = solveTopPlans(baseBoard, waits, curScore, targetScore, mode, topK, stageNo, isFreshStageScan);
            ModeAdvice rootAdvice = computeSolverModeAdvice(
                    stageNo, baseBoard, curScore, targetScore, mode,
                    (plans != null && !plans.isEmpty()) ? plans.get(0) : null,
                    isFreshStageScan
            );
            JSONObject root = new JSONObject();
            root.put("suggestedMode", rootAdvice.suggestedMode);
            root.put("suggestedModeTitleRu", rootAdvice.suggestedModeTitleRu);
            root.put("suggestedModeReasonRu", rootAdvice.suggestedModeReasonRu);
            root.put("suggestedModeUrgent", rootAdvice.urgent);
            JSONArray arr = new JSONArray();
            if (plans != null) {
                for (SolvePlan p : plans) {
                    JSONObject po = new JSONObject();
                    po.put("rank", p.rank);
                    po.put("evalScore", p.evalScore);
                    po.put("finalScore", p.finalScore);
                    po.put("totalGainedScore", p.totalGainedScore);
                    po.put("totalComboBonus", p.totalComboBonus);
                    po.put("finalEmptyCells", p.finalEmptyCells);
                    po.put("isWin", p.isWin);
                    po.put("mergeProbNextHand", p.mergeProbNextHand);
                    po.put("rerollTipOk", boosterTipsAllowed(AppState.get() != null ? AppState.get().boostRefresh : -1));
                    po.put("burstProbNextHand", p.burstProbNextHand);
                    po.put("recommendReroll", p.recommendReroll);
                    po.put("partialBeforeReroll", p.partialBeforeReroll);
                    po.put("deadlockStopAll", p.deadlockStopAll);
                    po.put("rerollReason", p.rerollReason != null ? p.rerollReason : "");
                    po.put("hammerCellId", p.hammerCellId);
                    po.put("hammerReasonRu", p.hammerReasonRu != null ? p.hammerReasonRu : "");
                    po.put("suggestedMode", p.suggestedMode != null ? p.suggestedMode : "");
                    po.put("suggestedModeTitleRu", p.suggestedModeTitleRu != null ? p.suggestedModeTitleRu : "");
                    po.put("suggestedModeReasonRu", p.suggestedModeReasonRu != null ? p.suggestedModeReasonRu : "");
                    po.put("suggestedModeUrgent", p.suggestedModeUrgent);

                    JSONArray oc = new JSONArray();
                    if (p.openColors != null) for (int c : p.openColors) oc.put(c);
                    po.put("openColors", oc);

                    JSONArray bc = new JSONArray();
                    if (p.burstReadyColors != null) for (int c : p.burstReadyColors) bc.put(c);
                    po.put("burstReadyColors", bc);

                    JSONArray ww = new JSONArray();
                    if (p.walledWarnings != null) for (String w : p.walledWarnings) ww.put(w);
                    po.put("walledWarnings", ww);

                    JSONArray stepsArr = new JSONArray();
                    for (StepMove sm : p.steps) {
                        JSONObject so = new JSONObject();
                        so.put("step", sm.stepNum);
                        so.put("slotId", sm.slotId);
                        so.put("targetCellId", sm.targetCellId);
                        so.put("gainedScore", sm.gainedScore);
                        so.put("scoreAfter", sm.scoreAfter);
                        so.put("emptyCellsAfter", sm.emptyAfter);
                        JSONArray cl = new JSONArray();
                        for (Layer l : sm.cardLayers) {
                            JSONObject lo = new JSONObject();
                            lo.put("colour", l.colour);
                            lo.put("count", l.count);
                            cl.put(lo);
                        }
                        so.put("cardLayers", cl);
                        JSONArray ul = new JSONArray();
                        if (sm.unlockedCells != null) for (int u : sm.unlockedCells) ul.put(u);
                        so.put("unlockedCells", ul);
                        if (sm.boardAfter != null) {
                            so.put("boardAfter", serializeBoardCellsJson(sm.boardAfter));
                        }
                        stepsArr.put(so);
                    }
                    po.put("steps", stepsArr);
                    arr.put(po);
                }
            }
            root.put("plans", arr);
            return root.toString();
        } catch (Exception e) {
            return "{\"plans\":[],\"error\":\"" + String.valueOf(e.getMessage()).replace("\"", "'") + "\"}";
        }
    }

    // --- TRAINED v11 3D-GEOMETRIC & Z-BUFFER COMPUTER VISION ANALYZER ---
    public static class VisionOutput {
        public Calibration calibration;
        public int detectedStageNo = -1;
        public int detectedScore = -1;
        public int detectedTargetScore = -1;
        // Инвентарь бустеров игрока (снизу экрана): -1 = панель не распознана
        public int boostHammer = -1;
        public int boostMove = -1;
        public int boostRefresh = -1;
        public CellState[] cells;
        public Map<Integer, List<Layer>> waits = new HashMap<>();
        public float[] measuredHeightSlices = new float[MAX_POS_COUNT + 1];
        public int[][] cellThumbRgb = new int[MAX_POS_COUNT + 1][];
        public boolean[] popupCovered = new boolean[MAX_POS_COUNT + 1];
        public JSONObject webCompatibleJson;
    }

    private static final int[][] CHIP_CAP_RGB = {
            {0, 0, 0},
            {236, 68, 58},   // 1 Ягоды (Red)
            {253, 143, 51},  // 2 Клён (Orange — calibrated from ground-truth frames)
            {247, 194, 36},  // 3 Колос (Yellow — calibrated across 39 Stage 7 frames)
            {149, 243, 48},  // 4 Орёл (Green)
            {56, 235, 203},  // 5 Хлеб (Cyan)
            {77, 189, 255},  // 6 Яблоко (Blue)
            {186, 106, 240}, // 7 Жёлудь (Purple)
            {255, 73, 131}   // 8 Индейка (Pink)
    };

    private static final int[][] CHIP_SIDE_RGB = {
            {0, 0, 0},
            {191, 52, 47},   // 1 Ягоды
            {206, 107, 31},  // 2 Клён
            {201, 143, 12},  // 3 Колос (Yellow side skirt — prevents Колос->Клён confusion)
            {88, 154, 45},   // 4 Орёл
            {26, 170, 155},  // 5 Хлеб
            {46, 138, 212},  // 6 Яблоко
            {156, 54, 233},  // 7 Жёлудь
            {212, 44, 106}   // 8 Индейка
    };

    private static final double[] CHIP_PROTO_HUE = {
            0.0, 1.0, 13.5, 21.5, 45.0, 86.0, 101.5, 137.5, 169.5
    };

    private static final char[] GLYPH_CHARS = {'0', '1', '2', '3', '4', '5', '6', '7', '8', '9', '/'};
    private static final String[] GLYPH_BITS_8X12 = {
            "004775100599997139955994596038979950349799513399995323999963049869730797499548941799998102799720",
            "247994005699950088799500444895000128950000289500002895000028950000289500444897449999999999999999",
            "246896304799999268645996244016991310179900004993001499500059951004996000499833338999999989999999",
            "379999604999999225448985000258540025865202799960026789920011289733100599865449976999999326999930",
            "000499500007995000499950007889500593795019617950693079509987998799999999555589850000795000007950",
            "299999922999999229844441298221003998884049999993343138970000059933100698866468976999999326999840",
            "002698310269964214786232368643005999996169999994897106989940049999500499599549961899998102899820",
            "999999999999999934445998000049930000798000018950000499200018970000399300008970000289500005982000",
            "026776202899997269835994794008955981399317999970389999718971189599400499897228964998899404999940",
            "015885101899997149955995797006979960059969811799399889970599999400246862042688400469953003997200",
            "000003990000189400003992000089500004982000079500004981000179500003991000179400003981000099500000"
    };
    private static final int[] VALID_TARGET_SCORES = {80, 180, 300, 350, 400, 450, 500, 550, 600};

    private static int[] rgbToHsvOpenCv(int r, int g, int b) {
        int mx = Math.max(r, Math.max(g, b));
        int mn = Math.min(r, Math.min(g, b));
        int d = mx - mn;
        double h = 0.0;
        if (d > 0) {
            if (mx == r) h = (((double) (g - b) / d) + (g < b ? 6.0 : 0.0)) * 30.0;
            else if (mx == g) h = (((double) (b - r) / d) + 2.0) * 30.0;
            else h = (((double) (r - g) / d) + 4.0) * 30.0;
        }
        int s = mx == 0 ? 0 : (int) Math.round((d * 255.0) / mx);
        return new int[]{((int) Math.round(h)) % 180, s, mx};
    }

    private static int medianInt(List<Integer> list) {
        if (list.isEmpty()) return 0;
        List<Integer> cp = new ArrayList<>(list);
        Collections.sort(cp);
        return cp.get(cp.size() / 2);
    }

    private static double medianDouble(List<Double> list) {
        if (list.isEmpty()) return 0.0;
        List<Double> cp = new ArrayList<>(list);
        Collections.sort(cp);
        return cp.get(cp.size() / 2);
    }

    private static double percentileInt(List<Integer> list, double qPct) {
        if (list.isEmpty()) return 0.0;
        List<Integer> cp = new ArrayList<>(list);
        Collections.sort(cp);
        int idx = Math.max(0, Math.min(cp.size() - 1, (int) Math.round((qPct / 100.0) * (cp.size() - 1))));
        return cp.get(idx);
    }

    private static int classifySinglePixel(
            int r, int g, int b,
            int[][] capProto, int[][] sideProto, double[] hueProto, boolean capMode
    ) {
        int[] hsv = rgbToHsvOpenCv(r, g, b);
        if (hsv[1] < 65 || hsv[2] < (capMode ? 65 : 98)) return 0;
        int bestCid = 1;
        double bestLoss = 1e18;
        for (int cid = 1; cid <= 8; cid++) {
            double dCap = Math.sqrt(
                    Math.pow(r - capProto[cid][0], 2) +
                    Math.pow(g - capProto[cid][1], 2) +
                    Math.pow(b - capProto[cid][2], 2)
            );
            double dSide = Math.sqrt(
                    Math.pow(r - sideProto[cid][0], 2) +
                    Math.pow(g - sideProto[cid][1], 2) +
                    Math.pow(b - sideProto[cid][2], 2)
            );
            double rgbD = capMode ? dCap : Math.min(dSide, 0.85 * dCap + 12.0);
            double dh = Math.abs(hsv[0] - hueProto[cid]);
            dh = Math.min(dh, 180.0 - dh);
            double loss = rgbD + 2.8 * dh;
            if (loss < bestLoss) {
                bestLoss = loss;
                bestCid = cid;
            }
        }
        return bestCid;
    }

    private static int classifyChipPixels(
            List<Integer> rs, List<Integer> gs, List<Integer> bs,
            int[][] capProto, int[][] sideProto, double[] hueProto, boolean capMode
    ) {
        if (rs.isEmpty()) return 0;
        if (!capMode) {
            // Per-pixel majority vote prevents blending across anti-aliased layer seams
            int[] votes = new int[9];
            int totalVotes = 0;
            for (int i = 0; i < rs.size(); i++) {
                int cid = classifySinglePixel(rs.get(i), gs.get(i), bs.get(i), capProto, sideProto, hueProto, false);
                if (cid >= 1 && cid <= 8) {
                    votes[cid]++;
                    totalVotes++;
                }
            }
            if (totalVotes >= 3) {
                int bestCid = 1, bestV = -1;
                for (int cid = 1; cid <= 8; cid++) {
                    if (votes[cid] > bestV) {
                        bestV = votes[cid];
                        bestCid = cid;
                    }
                }
                return bestCid;
            }
        }
        int mr = medianInt(rs), mg = medianInt(gs), mb = medianInt(bs);
        return classifySinglePixel(mr, mg, mb, capProto, sideProto, hueProto, capMode);
    }

    private static class GlyphBox {
        int x, y, w, h;
        byte[] bm;
    }

    private static List<GlyphBox> extractBinaryGlyphs(byte[] mask, int rw, int rh, int minGh, int maxGh, int minArea) {
        boolean[] vis = new boolean[rw * rh];
        int[] q = new int[rw * rh];
        List<GlyphBox> out = new ArrayList<>();
        for (int y = 0; y < rh; y++) {
            for (int x = 0; x < rw; x++) {
                int idx0 = y * rw + x;
                if (mask[idx0] == 0 || vis[idx0]) continue;
                int head = 0, tail = 0;
                q[tail++] = idx0;
                vis[idx0] = true;
                int x0 = x, x1 = x, y0 = y, y1 = y;
                while (head < tail) {
                    int cur = q[head++];
                    int cy = cur / rw, cx = cur - cy * rw;
                    if (cx < x0) x0 = cx;
                    if (cx > x1) x1 = cx;
                    if (cy < y0) y0 = cy;
                    if (cy > y1) y1 = cy;
                    for (int dy = -1; dy <= 1; dy++) {
                        for (int dx = -1; dx <= 1; dx++) {
                            if (dx == 0 && dy == 0) continue;
                            int nx = cx + dx, ny = cy + dy;
                            if (nx >= 0 && nx < rw && ny >= 0 && ny < rh) {
                                int ni = ny * rw + nx;
                                if (mask[ni] != 0 && !vis[ni]) {
                                    vis[ni] = true;
                                    q[tail++] = ni;
                                }
                            }
                        }
                    }
                }
                int gw = x1 - x0 + 1, gh = y1 - y0 + 1;
                if (gh >= minGh && gh <= maxGh && gw >= 2 && tail >= minArea) {
                    GlyphBox gb = new GlyphBox();
                    gb.x = x0; gb.y = y0; gb.w = gw; gb.h = gh;
                    gb.bm = new byte[gw * gh];
                    for (int i = 0; i < tail; i++) {
                        int p = q[i];
                        int py = p / rw, px = p - py * rw;
                        gb.bm[(py - y0) * gw + (px - x0)] = 1;
                    }
                    out.add(gb);
                }
            }
        }
        return out;
    }

    private static char classifyGlyph(GlyphBox g) {
        double[] inp = new double[96];
        for (int ty = 0; ty < 12; ty++) {
            int iy0 = (ty * g.h) / 12;
            int iy1 = Math.min(g.h, Math.max(iy0 + 1, ((ty + 1) * g.h + 11) / 12));
            for (int tx = 0; tx < 8; tx++) {
                int ix0 = (tx * g.w) / 8;
                int ix1 = Math.min(g.w, Math.max(ix0 + 1, ((tx + 1) * g.w + 7) / 8));
                int sum = 0, cnt = 0;
                for (int y = iy0; y < iy1; y++) {
                    for (int x = ix0; x < ix1; x++) {
                        sum += g.bm[y * g.w + x];
                        cnt++;
                    }
                }
                inp[ty * 8 + tx] = cnt > 0 ? (double) sum / cnt : 0.0;
            }
        }
        double inpN = 0.0;
        for (double v : inp) inpN += v * v;
        inpN = Math.sqrt(inpN) + 1e-6;
        double aspect = (double) g.w / Math.max(1.0, g.h);

        // Game font sans-serif '1' vs '/' structural disambiguator (prevents '1' -> '3' over yellow progress bar)
        if (aspect <= 0.53) {
            double rightStem = 0.0, midLeft = 0.0, botLeft = 0.0;
            for (int r = 2; r < 12; r++) for (int c = 4; c < 8; c++) rightStem += inp[r * 8 + c];
            for (int r = 3; r < 10; r++) for (int c = 0; c < 3; c++) midLeft += inp[r * 8 + c];
            for (int r = 8; r < 12; r++) for (int c = 0; c < 3; c++) botLeft += inp[r * 8 + c];
            if (rightStem / 40.0 >= 0.52 && midLeft / 21.0 <= 0.18 && botLeft / 12.0 <= 0.18) {
                return '1';
            }
        }

        char bestCh = '0';
        double bestSim = -1e9;
        for (int ci = 0; ci < GLYPH_CHARS.length; ci++) {
            char ch = GLYPH_CHARS[ci];
            String bits = GLYPH_BITS_8X12[ci];
            double dot = 0.0, tN = 0.0;
            for (int i = 0; i < 96; i++) {
                double tv = (bits.charAt(i) - '0') / 9.0;
                dot += inp[i] * tv;
                tN += tv * tv;
            }
            double sim = dot / (inpN * (Math.sqrt(tN) + 1e-6));
            if (ch == '/' && aspect > 0.64) continue;
            if (ch == '/' && aspect > 0.56) sim -= 0.18;
            if (ch == '1' && aspect > 0.58) sim -= 0.14;
            if (ch != '1' && ch != '/' && aspect < 0.42) sim -= 0.20;
            if (ch == '5') {
                double tr = 0.0, bl = 0.0;
                for (int r = 2; r < 5; r++) for (int c = 5; c < 8; c++) tr += inp[r * 8 + c];
                for (int r = 7; r < 10; r++) for (int c = 0; c < 3; c++) bl += inp[r * 8 + c];
                sim += 0.08 * ((1.0 - tr / 9.0) + (1.0 - bl / 9.0) - 1.0);
            }
            if (sim > bestSim) {
                bestSim = sim;
                bestCh = ch;
            }
        }
        return bestCh;
    }

    // ===================== БУСТЕРЫ: ГЕЙТ СОВЕТОВ =====================
    /** Советы по бустерам (🔨 молоток / 🔄 обновить / переместить) показываются ТОЛЬКО при ВКЛ
     *  «Учитывать подсказки» и известном ненулевом запасе (0 = «нет бустера»; -1 = панель не
     *  распознана — не блокируем). */
    public static boolean boosterTipsAllowed(int boostCount) {
        AppState ap = AppState.get();
        if (ap == null || !ap.useHintsInCalc) return false;
        return boostCount != 0;
    }

    // ===================== ПРОГНОЗ СЛЕДУЮЩЕЙ РУКИ: СВЕРКА =====================
    private static List<List<Layer>> sNextHandPredicted;
    private static String sPrevHandSig;
    private static int sPredCheck = -1;

    private static String slotSig(List<Layer> layers) {
        if (layers == null || layers.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        for (Layer l : layers) sb.append(l.colour).append('_').append(l.count).append('|');
        return sb.toString();
    }

    private static List<List<Layer>> handToSlots(List<HandItem> hand) {
        if (hand == null) return null;
        List<List<Layer>> out = new ArrayList<>(hand.size());
        for (HandItem hi : hand) out.add(hi.layers);
        return out;
    }

    private static String slotsSig(List<List<Layer>> slots) {
        if (slots == null || slots.isEmpty()) return null;
        List<String> sigs = new ArrayList<>();
        for (List<Layer> sl : slots) {
            String sg = slotSig(sl);
            if (sg.isEmpty()) return null;
            sigs.add(sg);
        }
        Collections.sort(sigs);
        return String.join(",", sigs);
    }

    private static String waitsSig(Map<Integer, List<Layer>> waits) {
        if (waits == null) return null;
        List<List<Layer>> slots = new ArrayList<>(3);
        for (int sid = 1; sid <= 3; sid++) {
            List<Layer> w = waits.get(sid);
            slots.add(w != null ? w : new ArrayList<Layer>());
        }
        return slotsSig(slots);
    }

    /** Сколько из3 предсказанных стопок реально пришло (множественное сравнение сигнатур). */
    private static int matchCount(List<List<Layer>> pred, List<List<Layer>> actual) {
        if (pred == null || actual == null) return -1;
        List<String> pa = new ArrayList<>();
        for (List<Layer> sl : pred) pa.add(slotSig(sl));
        List<String> aa = new ArrayList<>();
        for (List<Layer> sl : actual) aa.add(slotSig(sl));
        int matched = 0;
        for (String p : pa) {
            int ix = aa.indexOf(p);
            if (ix >= 0) { matched++; aa.remove(ix); }
        }
        return matched;
    }

    /** Проверка прогноза на момент СКАНА руки (лог): если рука уже сменилась — сверяем сразу. */
    public static int peekPredCheckForWaits(Map<Integer, List<Layer>> waits) {
        synchronized (SolverEngine.class) {
            String curSig = waitsSig(waits);
            if (curSig == null || sPrevHandSig == null) return sPredCheck;
            if (curSig.equals(sPrevHandSig)) return sPredCheck;
            if (sNextHandPredicted == null) return -1;
            List<List<Layer>> actual = new ArrayList<>(3);
            for (int sid = 1; sid <= 3; sid++) {
                List<Layer> w = waits.get(sid);
                actual.add(w != null ? w : new ArrayList<Layer>());
            }
            return matchCount(sNextHandPredicted, actual);
        }
    }

    public static List<List<Layer>> getLastNextHandPredicted() {
        synchronized (SolverEngine.class) { return sNextHandPredicted; }
    }

    public static int getLastHandPredCheck() {
        synchronized (SolverEngine.class) { return sPredCheck; }
    }

    // ===================== ДЕТЕКТОР ПАНЕЛИ БУСТЕРОВ =====================
    /** Панель бустеров внизу экрана: [🔨][✚][🔄] с красными бейджами-счётчиками.
     *  @return {hammer, move, refresh} либо {-1,-1,-1}, если панель не видна. */
    static int[] detectBoosters(int[] px, int W, int H, int[] hsvH, int[] hsvS, int[] hsvV) {
        int[] res = {-1, -1, -1};
        if (W < 160 || H < 240) return res;
        int y0 = (int) (0.855 * H), y1 = Math.min(H - 1, (int) (0.995 * H));
        if (y1 - y0 < 14) return res;
        int spanY = y1 - y0 + 1;
        int[] colBtn = new int[W];
        int[] colRed = new int[W];
        for (int y = y0; y <= y1; y++) {
            for (int x = 0; x < W; x++) {
                int i = y * W + x;
                int h = hsvH[i], sc = hsvS[i], vc = hsvV[i];
                int r = (px[i] >> 16) & 0xFF, g = (px[i] >> 8) & 0xFF, b = px[i] & 0xFF;
                // кнопки: оранжево-жёлтые
                if (h >= 14 && h <= 48 && sc >= 110 && vc >= 140 && r > 150 && b < 130) colBtn[x]++;
                // красные бейджи-счётчики
                if (r >= 160 && g <= 95 && b <= 95 && (r - g) >= 90) colRed[x]++;
            }
        }
        // кластеры колонок
        List<int[]> btnRuns = new ArrayList<>(), redRuns = new ArrayList<>();
        collectRuns(colBtn, spanY, 0.16, btnRuns);
        collectRuns(colRed, spanY, 0.07, redRuns);
        if (btnRuns.size() < 3 || redRuns.isEmpty()) return res;
        // три ЛЕВЫЕ кнопки (hammer, move, refresh) — отбраковка узких
        btnRuns.sort((x, y) -> Integer.compare(x[0], y[0]));
        List<int[]> btn3 = new ArrayList<>();
        for (int[] r : btnRuns) {
            if (r[1] - r[0] + 1 >= Math.max(6, (int) (0.045 * W))) btn3.add(r);
            if (btn3.size() == 3) break;
        }
        if (btn3.size() < 3) return res;
        int[] out = {-1, -1, -1};
        for (int bi = 0; bi < 3; bi++) {
            int[] btn = btn3.get(bi);
            int bC = (btn[0] + btn[1]) / 2;
            // бейдж: красный кластер у правого верхнего угла кнопки
            int[] badge = null;
            int bestD = Integer.MAX_VALUE;
            for (int[] rr : redRuns) {
                int rc = (rr[0] + rr[1]) / 2;
                if (rc < btn[0] - (int) (0.03 * W) || rc > btn[1] + (int) (0.06 * W)) continue;
                int d = Math.abs(rc - bC);
                if (d < bestD) { bestD = d; badge = rr; }
            }
            if (badge == null) continue;
            // bbox бейджа по y внутри x-диапазона
            int bx0 = Math.max(0, badge[0] - 2), bx1 = Math.min(W - 1, badge[1] + 2);
            int by0 = Integer.MAX_VALUE, by1 = -1;
            for (int y = y0; y <= y1; y++) {
                for (int x = bx0; x <= bx1; x++) {
                    int i = y * W + x;
                    int r = (px[i] >> 16) & 0xFF, g = (px[i] >> 8) & 0xFF, b = px[i] & 0xFF;
                    if (r >= 160 && g <= 95 && b <= 95 && (r - g) >= 90) {
                        if (y < by0) by0 = y;
                        if (y > by1) by1 = y;
                    }
                }
            }
            if (by1 < by0 || by1 - by0 < 5) continue;
            // цифры бейджа: белые пиксели внутри
            int rw = bx1 - bx0 + 1, rh = by1 - by0 + 1;
            byte[] wMask = new byte[rw * rh];
            for (int y = 0; y < rh; y++) {
                for (int x = 0; x < rw; x++) {
                    int i = (by0 + y) * W + (bx0 + x);
                    int r = (px[i] >> 16) & 0xFF, g = (px[i] >> 8) & 0xFF, b = px[i] & 0xFF;
                    int sc = hsvS[i];
                    if (Math.min(r, Math.min(g, b)) >= 140 && sc <= 95) wMask[y * rw + x] = 1;
                }
            }
            List<GlyphBox> glyphs = extractBinaryGlyphs(
                    wMask, rw, rh, Math.max(4, (int) Math.round(H * 0.007)),
                    Math.max(12, (int) Math.round(H * 0.030)), 3);
            if (glyphs.isEmpty()) continue;
            // фильтр по общей линии цифр
            List<Double> cys = new ArrayList<>(), hs = new ArrayList<>();
            for (GlyphBox g : glyphs) { cys.add(g.y + 0.5 * g.h); hs.add((double) g.h); }
            double medCy = medianDouble(cys), medH = medianDouble(hs);
            List<GlyphBox> filt = new ArrayList<>();
            for (GlyphBox g : glyphs) {
                if (Math.abs((g.y + 0.5 * g.h) - medCy) <= 0.8 * medH && g.h >= 0.6 * medH) filt.add(g);
            }
            if (filt.isEmpty()) continue;
            Collections.sort(filt, (a, b2) -> Integer.compare(a.x, b2.x));
            StringBuilder sb = new StringBuilder();
            for (GlyphBox g : filt) {
                char ch = classifyGlyph(g);
                if (ch >= '0' && ch <= '9') sb.append(ch);
            }
            if (sb.length() == 0 || sb.length() > 2) continue;
            try {
                int cnt = Integer.parseInt(sb.toString());
                out[bi] = Math.min(cnt, 99);
            } catch (NumberFormatException ignore) {}
        }
        // панель считается распознанной, только если найдены все3 кнопки и хотя бы один бейдж
        if (out[0] < 0 && out[1] < 0 && out[2] < 0) return res;
        return out;
    }

    private static void collectRuns(int[] cols, int spanY, double fill, List<int[]> runs) {
        int thr = Math.max(2, (int) Math.round(fill * spanY));
        int runS = -1;
        for (int x = 0; x < cols.length; x++) {
            if (cols[x] >= thr) {
                if (runS < 0) runS = x;
            } else {
                if (runS >= 0 && x - runS >= 4) runs.add(new int[]{runS, x - 1});
                runS = -1;
            }
        }
        if (runS >= 0 && cols.length - runS >= 4) runs.add(new int[]{runS, cols.length - 1});
    }

    private static int[] detectHeaderStageAndScore(
            int[] px, int W, int H, int[] hsvS, int[] hsvV, Map<Integer, StageCfg> allStages
    ) {
        // Returns [detectedStageNo (-1 if none), detectedScore (-1 if none), detectedTargetScore (-1 if none)]
        int[] res = {-1, -1, -1};
        if (H < 240 || W < 160) return res;

        int x0 = (int) (0.26 * W), x1 = (int) (0.74 * W);
        int gameTopY = 0;
        for (int y = 0; y < (int) (0.20 * H); y++) {
            int darkRow = 0;
            for (int x = (int) (0.15 * W); x < (int) (0.85 * W); x += 4) {
                if (hsvV[y * W + x] < 52) darkRow++;
            }
            int tot = Math.max(1, ((int) (0.85 * W) - (int) (0.15 * W) + 3) / 4);
            if ((double) darkRow / tot >= 0.85) gameTopY = y + 1;
            else break;
        }
        int gameBotHdr = H;
        for (int y = H - 1; y >= (int) (0.78 * H); y--) {
            int darkRow = 0;
            for (int x = (int) (0.15 * W); x < (int) (0.85 * W); x += 4) {
                if (hsvV[y * W + x] < 52) darkRow++;
            }
            int tot = Math.max(1, ((int) (0.85 * W) - (int) (0.15 * W) + 3) / 4);
            if ((double) darkRow / tot >= 0.85) gameBotHdr = y;
            else break;
        }
        int gameH = Math.max(200, gameBotHdr - gameTopY);
        int yScan0 = Math.min(H - 8, gameTopY + (int) (0.08 * gameH));
        int yScan1 = Math.min(H - 2, gameTopY + (int) (0.25 * gameH));
        List<Integer> barRows = new ArrayList<>();
        for (int y = yScan0; y < yScan1; y++) {
            int darkCnt = 0;
            int barTrackCnt = 0;
            for (int x = x0; x < x1; x++) {
                int idx = y * W + x;
                int vc = hsvV[idx];
                int sc = hsvS[idx];
                if (vc < 98) darkCnt++;
                // Count EITHER dark empty track OR bright yellow/amber fill bar so >60% progress bars are always found!
                if (vc < 98 || (sc >= 125 && vc >= 165)) barTrackCnt++;
            }
            int span = Math.max(1, x1 - x0);
            if ((double) darkCnt / span >= 0.22 || (double) barTrackCnt / span >= 0.52) {
                barRows.add(y - yScan0);
            }
        }

        List<int[]> candBands = new ArrayList<>();
        if (barRows.size() >= 4) {
            int rs = barRows.get(0), prev = rs;
            for (int i = 1; i < barRows.size(); i++) {
                int r = barRows.get(i);
                if (r - prev > 4) {
                    candBands.add(new int[]{Math.max(0, yScan0 + rs - 2), Math.min(H, yScan0 + prev + 3)});
                    rs = r;
                }
                prev = r;
            }
            candBands.add(new int[]{Math.max(0, yScan0 + rs - 2), Math.min(H, yScan0 + prev + 3)});
        }
        candBands.add(new int[]{Math.min(H - 8, gameTopY + (int) (0.095 * gameH)), Math.min(H - 2, gameTopY + (int) (0.155 * gameH))});
        candBands.add(new int[]{Math.min(H - 8, gameTopY + (int) (0.145 * gameH)), Math.min(H - 2, gameTopY + (int) (0.205 * gameH))});

        int ys0 = Math.min(H - 8, gameTopY + (int) (0.145 * gameH));
        int rw = x1 - x0;
        for (int[] band : candBands) {
            int bY0 = band[0], bY1 = band[1];
            int rh = bY1 - bY0;
            if (rw <= 10 || rh <= 5 || rh > (int) (0.085 * gameH)) continue;
            int maxMinRgb = 0;
            byte[] darkNear = new byte[rw * rh];
            for (int y = 0; y < rh; y++) {
                for (int x = 0; x < rw; x++) {
                    byte hasD = 0;
                    for (int dy = -4; dy <= 4 && hasD == 0; dy++) {
                        int yy = Math.min(H - 1, Math.max(0, bY0 + y + dy));
                        for (int dx = -4; dx <= 4; dx++) {
                            int xx = Math.min(W - 1, Math.max(0, x0 + x + dx));
                            // Dark outline around white digits stays < 160 even when anti-aliased over bright golden fill
                            if (hsvV[yy * W + xx] < 160) { hasD = 1; break; }
                        }
                    }
                    darkNear[y * rw + x] = hasD;
                    if (hasD != 0) {
                        int c = px[(bY0 + y) * W + (x0 + x)];
                        int mn = Math.min((c >> 16) & 0xFF, Math.min((c >> 8) & 0xFF, c & 0xFF));
                        if (mn > maxMinRgb) maxMinRgb = mn;
                    }
                }
            }
            double thr = Math.max(162.0, 0.72 * maxMinRgb);
            byte[] wMask = new byte[rw * rh];
            for (int y = 0; y < rh; y++) {
                for (int x = 0; x < rw; x++) {
                    if (darkNear[y * rw + x] == 0) continue;
                    int idx = (bY0 + y) * W + (x0 + x);
                    int c = px[idx];
                    int mn = Math.min((c >> 16) & 0xFF, Math.min((c >> 8) & 0xFF, c & 0xFF));
                    if (mn >= thr && hsvS[idx] <= 102) wMask[y * rw + x] = 1;
                }
            }
            List<GlyphBox> glyphs = extractBinaryGlyphs(
                    wMask, rw, rh, Math.max(4, (int) Math.round(H * 0.006)), Math.max(16, (int) Math.round(H * 0.036)), 4
            );
            if (glyphs.size() >= 3) {
                List<Double> cys = new ArrayList<>(), hs = new ArrayList<>();
                for (GlyphBox g : glyphs) { cys.add(g.y + 0.5 * g.h); hs.add((double) g.h); }
                double medCy = medianDouble(cys), medH = medianDouble(hs);
                List<GlyphBox> filt = new ArrayList<>();
                for (GlyphBox g : glyphs) {
                    // Wide digits like '4' over golden progress bar can lose 1px at apex/foot (h ≈ 0.68*medH),
                    // whereas fragmented '/' pieces have narrow width (g.w < 0.50*medH).
                    double minGlyphH = (g.w >= 0.50 * medH) ? (0.64 * medH) : (0.74 * medH);
                    if (Math.abs((g.y + 0.5 * g.h) - medCy) <= 0.75 * medH && g.h >= minGlyphH) {
                        filt.add(g);
                    }
                }
                if (filt.size() < 3) continue;
                Collections.sort(filt, (a, b) -> Integer.compare(a.x, b.x));
                StringBuilder sb = new StringBuilder();
                for (GlyphBox g : filt) sb.append(classifyGlyph(g));
                String rawS = sb.toString();

                String leftD = "";
                String rightD = "";
                int sl = rawS.indexOf('/');
                if (sl > 0 && sl < rawS.length() - 1) {
                    leftD = rawS.substring(0, sl).replaceAll("\\D", "");
                    rightD = rawS.substring(sl + 1).replaceAll("\\D", "");
                } else if (filt.size() >= 3) {
                    // Yellow fill bar covered '/' -> split by the widest horizontal X-gap where '/' sits!
                    int bestSplit = -1;
                    int maxGap = -999;
                    for (int i = 1; i <= filt.size() - 2; i++) {
                        int gap = filt.get(i).x - (filt.get(i - 1).x + filt.get(i - 1).w);
                        if (gap > maxGap) {
                            maxGap = gap;
                            bestSplit = i;
                        }
                    }
                    if (bestSplit > 0 && maxGap >= Math.max(3, (int) Math.round(0.54 * medH))) {
                        leftD = rawS.substring(0, bestSplit).replaceAll("\\D", "");
                        rightD = rawS.substring(bestSplit).replaceAll("\\D", "");
                    }
                }

                if (!leftD.isEmpty() && !rightD.isEmpty()) {
                    try {
                        int curVal = Integer.parseInt(leftD);
                        int rawTgt = Integer.parseInt(rightD);
                        int bestTgt = VALID_TARGET_SCORES[0];
                        int bestCost = Integer.MAX_VALUE;
                        for (int t : VALID_TARGET_SCORES) {
                            String ts = String.valueOf(t);
                            int hd = 0;
                            for (int k = 0; k < Math.min(ts.length(), rightD.length()); k++) {
                                if (ts.charAt(k) != rightD.charAt(k)) hd++;
                            }
                            int cost = Math.abs(ts.length() - rightD.length()) * 200 + hd * 50 + Math.abs(t - rawTgt);
                            if (cost < bestCost) { bestCost = cost; bestTgt = t; }
                        }
                        if (Math.abs(bestTgt - rawTgt) <= 50 || bestCost <= 90) {
                            res[2] = bestTgt;
                            if (curVal >= 0 && curVal <= bestTgt * 3) res[1] = curVal;
                            ys0 = bY0;
                            break;
                        }
                    } catch (Exception ignored) {}
                }
            }
        }

        // Stage pill ("Этап N")
        int yp0 = Math.max(0, ys0 - (int) (0.095 * H));
        int yp1 = Math.max(yp0 + 8, ys0 - 2);
        int xp0 = (int) (0.28 * W), xp1 = (int) (0.72 * W);
        int sw = xp1 - xp0, sh = yp1 - yp0;
        int stageCand = -1;
        if (sw > 10 && sh > 6) {
            int maxSMin = 0;
            byte[] sDark = new byte[sw * sh];
            for (int y = 0; y < sh; y++) {
                for (int x = 0; x < sw; x++) {
                    byte hasD = 0;
                    for (int dy = -4; dy <= 4 && hasD == 0; dy++) {
                        int yy = Math.min(H - 1, Math.max(0, yp0 + y + dy));
                        for (int dx = -4; dx <= 4; dx++) {
                            int xx = Math.min(W - 1, Math.max(0, xp0 + x + dx));
                            if (hsvV[yy * W + xx] < 130) { hasD = 1; break; }
                        }
                    }
                    sDark[y * sw + x] = hasD;
                    if (hasD != 0) {
                        int c = px[(yp0 + y) * W + (xp0 + x)];
                        int mn = Math.min((c >> 16) & 0xFF, Math.min((c >> 8) & 0xFF, c & 0xFF));
                        if (mn > maxSMin) maxSMin = mn;
                    }
                }
            }
            double sthr = Math.max(172.0, 0.76 * maxSMin);
            byte[] swMask = new byte[sw * sh];
            for (int y = 0; y < sh; y++) {
                for (int x = 0; x < sw; x++) {
                    if (sDark[y * sw + x] == 0) continue;
                    int idx = (yp0 + y) * W + (xp0 + x);
                    int c = px[idx];
                    int mn = Math.min((c >> 16) & 0xFF, Math.min((c >> 8) & 0xFF, c & 0xFF));
                    if (mn >= sthr && hsvS[idx] <= 60) swMask[y * sw + x] = 1;
                }
            }
            List<GlyphBox> sglyphs = extractBinaryGlyphs(
                    swMask, sw, sh, Math.max(6, (int) Math.round(H * 0.009)), Math.max(18, (int) Math.round(H * 0.042)), 6
            );
            if (sglyphs.size() >= 2) {
                List<Double> cys2 = new ArrayList<>(), hs2 = new ArrayList<>();
                for (GlyphBox g : sglyphs) { cys2.add(g.y + 0.5 * g.h); hs2.add((double) g.h); }
                double medCy2 = medianDouble(cys2), medH2 = medianDouble(hs2);
                List<GlyphBox> filt2 = new ArrayList<>();
                for (GlyphBox g : sglyphs) if (Math.abs((g.y + 0.5 * g.h) - medCy2) <= 0.75 * medH2) filt2.add(g);
                Collections.sort(filt2, (a, b) -> Integer.compare(a.x, b.x));
                if (filt2.size() >= 2) {
                    int maxGap = -1, splitIdx = filt2.size() - 1;
                    for (int i = 1; i < filt2.size(); i++) {
                        int g = filt2.get(i).x - (filt2.get(i - 1).x + filt2.get(i - 1).w);
                        if (g > maxGap) { maxGap = g; splitIdx = i; }
                    }
                    int startIdx = maxGap >= Math.max(4, (int) Math.round(medH2 * 0.36)) ? splitIdx : (filt2.size() - 1);
                    StringBuilder dsb = new StringBuilder();
                    for (int i = startIdx; i < filt2.size(); i++) {
                        char ch = classifyGlyph(filt2.get(i));
                        if (Character.isDigit(ch)) dsb.append(ch);
                    }
                    if (dsb.length() > 0) {
                        try {
                            int v = Integer.parseInt(dsb.toString());
                            if (v >= 1 && v <= 16) stageCand = v;
                        } catch (Exception ignored) {}
                    }
                }
            }
        }

        if (allStages != null && !allStages.isEmpty()) {
            List<Integer> byTgt = new ArrayList<>();
            if (res[2] > 0) {
                for (StageCfg s : allStages.values()) {
                    if (s.targetScore == res[2]) byTgt.add(s.stageNo);
                }
            }
            if (stageCand >= 1) {
                if (byTgt.isEmpty() || byTgt.contains(stageCand)) res[0] = stageCand;
                else if (byTgt.size() == 1) res[0] = byTgt.get(0);
                else res[0] = stageCand;
            } else if (byTgt.size() == 1) {
                res[0] = byTgt.get(0);
            }
        } else if (stageCand >= 1) {
            res[0] = stageCand;
        }
        return res;
    }

    private static double[] fitLinear1D(List<Double> us, List<Double> xs) {
        int n = us.size();
        if (n == 0) return new double[]{0.0, 1.0};
        double su = 0, sx = 0, suu = 0, sux = 0;
        for (int i = 0; i < n; i++) {
            double u = us.get(i), x = xs.get(i);
            su += u; sx += x; suu += u * u; sux += u * x;
        }
        double den = n * suu - su * su;
        if (Math.abs(den) < 1e-6) return new double[]{sx / n, 1.0};
        double slope = (n * sux - su * sx) / den;
        double inter = (sx - slope * su) / n;
        return new double[]{inter, slope};
    }

    private static List<double[]> extractHexTileBlobs(byte[] srcMask, int W, int H, int playY0, int playY1, boolean doClose) {
        int rw = W, rh = playY1 - playY0;
        byte[] work = new byte[rw * rh];
        for (int y = 0; y < rh; y++) {
            System.arraycopy(srcMask, (playY0 + y) * W, work, y * rw, rw);
        }
        if (doClose) {
            byte[] dil = new byte[rw * rh];
            for (int y = 3; y < rh - 3; y++) {
                for (int x = 3; x < rw - 3; x++) {
                    byte any = 0;
                    for (int dy = -3; dy <= 3 && any == 0; dy++) {
                        for (int dx = -3; dx <= 3; dx++) {
                            if (work[(y + dy) * rw + (x + dx)] != 0) { any = 1; break; }
                        }
                    }
                    dil[y * rw + x] = any;
                }
            }
            work = dil;
        }
        byte[] ero = new byte[rw * rh];
        for (int y = 2; y < rh - 2; y++) {
            for (int x = 2; x < rw - 2; x++) {
                byte all = 1;
                for (int dy = -2; dy <= 2 && all != 0; dy++) {
                    for (int dx = -2; dx <= 2; dx++) {
                        if (work[(y + dy) * rw + (x + dx)] == 0) { all = 0; break; }
                    }
                }
                ero[y * rw + x] = all;
            }
        }
        byte[] opn = new byte[rw * rh];
        for (int y = 2; y < rh - 2; y++) {
            for (int x = 2; x < rw - 2; x++) {
                if (ero[y * rw + x] == 0) continue;
                for (int dy = -2; dy <= 2; dy++) {
                    for (int dx = -2; dx <= 2; dx++) opn[(y + dy) * rw + (x + dx)] = 1;
                }
            }
        }
        boolean[] vis = new boolean[rw * rh];
        int[] q = new int[rw * rh];
        List<double[]> blobs = new ArrayList<>();
        double minArea = Math.pow(0.038 * W, 2);
        for (int y = 2; y < rh - 2; y++) {
            for (int x = 2; x < rw - 2; x++) {
                int i0 = y * rw + x;
                if (opn[i0] == 0 || vis[i0]) continue;
                int head = 0, tail = 0;
                q[tail++] = i0;
                vis[i0] = true;
                int bx0 = x, bx1 = x, by0 = y, by1 = y;
                long sx = 0, sy = 0;
                while (head < tail) {
                    int cur = q[head++];
                    int cy = cur / rw, cx = cur - cy * rw;
                    sx += cx; sy += cy;
                    if (cx < bx0) bx0 = cx;
                    if (cx > bx1) bx1 = cx;
                    if (cy < by0) by0 = cy;
                    if (cy > by1) by1 = cy;
                    int[] nbs = {cur - 1, cur + 1, cur - rw, cur + rw};
                    for (int nb : nbs) {
                        if (nb >= 0 && nb < rw * rh && opn[nb] != 0 && !vis[nb]) {
                            vis[nb] = true;
                            q[tail++] = nb;
                        }
                    }
                }
                int wb = bx1 - bx0 + 1, hb = by1 - by0 + 1;
                if (wb >= 0.08 * W && wb <= 0.25 * W && hb >= 0.04 * H && hb <= 0.15 * H && tail >= minArea) {
                    double ar = (double) wb / Math.max(1, hb);
                    if (ar >= 0.95 && ar <= 1.75) {
                        blobs.add(new double[]{(double) sx / tail, playY0 + (double) sy / tail, wb});
                    }
                }
            }
        }
        return blobs;
    }

    private static double[] fitBoardCamera(
            int[] px, List<double[]> activePts, double minUx, double maxUx, double maxUy,
            byte[] chipMask, byte[] emptyMask, byte[] lockedMask, int W, int H
    ) {
        // Pure hex-board foreground mask (chips + empty tiles + lock tiles), 100% immune to painted
        // background tents/flags, top-right golden chest (y < 0.225*H), and red danger border flash!
        int[] rowCnt = new int[H];
        byte[] boardFg = new byte[W * H];
        int scanY0 = (int) (0.225 * H), scanY1 = (int) (0.72 * H);

        for (int y = scanY0; y < scanY1; y++) {
            int cnt = 0;
            for (int x = 6; x < W - 6; x++) {
                int idx = y * W + x;
                int c = px[idx];
                int r = (c >> 16) & 0xFF, g = (c >> 8) & 0xFF, b = c & 0xFF;
                boolean neonAr = ((r < 55 && g > 180 && b > 80) || (r > 215 && g > 145 && b < 40 && (r - b) > 180));
                if (neonAr) continue;
                if (chipMask[idx] != 0 || emptyMask[idx] != 0 || lockedMask[idx] != 0) {
                    boardFg[idx] = 1;
                    cnt++;
                }
            }
            rowCnt[y] = cnt;
        }

        // Find clean horizontal valley between board bottom (~0.56..0.66*H) and hand tray top
        int valY = (int) (0.675 * H);
        for (int y = (int) (0.55 * H); y < (int) (0.71 * H); y++) {
            if (rowCnt[y] <= 2) {
                valY = y;
                break;
            }
        }
        // Find clean horizontal valley above board (below header / progress chest)
        int topY = (int) (0.228 * H);
        for (int y = (int) (0.31 * H); y >= (int) (0.225 * H); y--) {
            if (rowCnt[y] <= 2) {
                topY = y;
                break;
            }
        }

        int minColHits = Math.max(8, (int) (0.018 * H));
        int[] colCnt = new int[W];
        for (int x = 6; x < W - 6; x++) {
            int cc = 0;
            for (int y = topY; y < valY; y++) {
                if (boardFg[y * W + x] != 0) cc++;
            }
            colCnt[x] = cc;
        }
        List<Integer> xsB = new ArrayList<>(), ysB = new ArrayList<>();
        for (int y = topY; y < valY; y++) {
            for (int x = 6; x < W - 6; x++) {
                if (boardFg[y * W + x] != 0 && colCnt[x] >= minColHits) {
                    xsB.add(x);
                    ysB.add(y);
                }
            }
        }
        if (xsB.size() >= 120) {
            double xLeft = percentileInt(xsB, 0.4);
            double xRight = percentileInt(xsB, 99.6);
            double worldW100 = (maxUx - minUx) / 100.0 + 1.075;
            double sc = Math.max(0.105 * W, Math.min(0.215 * W, (xRight - xLeft) / Math.max(1.5, worldW100)));
            double sy = sc * 0.8695;
            double cx0 = 0.5 * (xLeft + xRight);
            double yBot = percentileInt(ysB, 99.6);
            double cy0 = yBot - (maxUy / 100.0) * sy - 0.428 * sc;
            return new double[]{cx0, cy0, sc, sy};
        }

        double sc = 0.160 * W;
        return new double[]{0.5 * W, 0.424 * H, sc, sc * 0.8695};
    }

    private static void fillPolygonMask(byte[] mask, int W, int H, double[][] pts) {
        int minY = H - 1, maxY = 0;
        for (double[] p : pts) {
            minY = Math.min(minY, Math.max(0, (int) Math.floor(p[1])));
            maxY = Math.max(maxY, Math.min(H - 1, (int) Math.ceil(p[1])));
        }
        int n = pts.length;
        double[] xs = new double[n];
        for (int y = minY; y <= maxY; y++) {
            int cnt = 0;
            for (int i = 0, j = n - 1; i < n; j = i++) {
                double xi = pts[i][0], yi = pts[i][1];
                double xj = pts[j][0], yj = pts[j][1];
                if ((yi > y) != (yj > y)) {
                    xs[cnt++] = xi + ((xj - xi) * (y - yi)) / (yj - yi);
                }
            }
            Arrays.sort(xs, 0, cnt);
            for (int k = 0; k + 1 < cnt; k += 2) {
                int xStart = Math.max(0, (int) Math.floor(xs[k]));
                int xEnd = Math.min(W - 1, (int) Math.ceil(xs[k + 1]));
                for (int x = xStart; x <= xEnd; x++) mask[y * W + x] = 1;
            }
        }
    }

    /**
     * Fast Header (Stage + Score) + Bottom 3 Hand Stacks scanner for Android overlay.
     * Deliberately NEVER scans or overwrites the hex board cells!
     */
    private static Bitmap trimEmulatorChromeBars(Bitmap src) {
        if (src == null) return null;
        int w = src.getWidth(), h = src.getHeight();
        if (w < 200 || h < 260) return src;
        int maxTrimX = Math.max(4, (int) (0.12 * w));
        int leftX = 0, rightX = w - 1;
        int[] vBuf = new int[25];
        int[] rBuf = new int[25];
        int[] cBuf = new int[25];

        for (int x = 0; x < maxTrimX; x++) {
            for (int i = 0; i < 25; i++) {
                int y = Math.min(h - 1, (int) Math.round(h * (0.22 + 0.022 * i)));
                int px = src.getPixel(x, y);
                int r = (px >> 16) & 0xFF, g = (px >> 8) & 0xFF, b = px & 0xFF;
                vBuf[i] = Math.max(r, Math.max(g, b));
                rBuf[i] = r;
                cBuf[i] = Math.max(r, Math.max(g, b)) - Math.min(r, Math.min(g, b));
            }
            Arrays.sort(vBuf);
            Arrays.sort(rBuf);
            Arrays.sort(cBuf);
            if (vBuf[12] < 96 && (cBuf[12] < 20 || rBuf[12] < 55)) leftX = x + 1;
            else break;
        }
        for (int x = w - 1; x >= w - maxTrimX; x--) {
            for (int i = 0; i < 25; i++) {
                int y = Math.min(h - 1, (int) Math.round(h * (0.22 + 0.022 * i)));
                int px = src.getPixel(x, y);
                int r = (px >> 16) & 0xFF, g = (px >> 8) & 0xFF, b = px & 0xFF;
                vBuf[i] = Math.max(r, Math.max(g, b));
                rBuf[i] = r;
                cBuf[i] = Math.max(r, Math.max(g, b)) - Math.min(r, Math.min(g, b));
            }
            Arrays.sort(vBuf);
            Arrays.sort(rBuf);
            Arrays.sort(cBuf);
            if (vBuf[12] < 96 && (cBuf[12] < 20 || rBuf[12] < 55)) rightX = x - 1;
            else break;
        }
        int newW = rightX - leftX + 1;
        if (newW < w - 4 && newW >= 180) {
            return Bitmap.createBitmap(src, leftX, 0, newW, h);
        }
        return src;
    }

    public static Bitmap cropSplitScreenGamePane(
            Bitmap rawBmp, Map<Integer, int[]> customPalette, Map<Integer, StageCfg> allStages
    ) {
        if (rawBmp == null) return null;
        if (rawBmp.getWidth() <= rawBmp.getHeight() * 1.02) {
            return trimEmulatorChromeBars(rawBmp);
        }
        int rw = rawBmp.getWidth(), rh = rawBmp.getHeight();
        int xLo = Math.max(rw / 4, rw - Math.round(rh * 0.72f));
        int xHi = Math.min((rw * 2) / 3, rw - Math.round(rh * 0.44f));
        int foundWarmX = -1;
        int warmStreak = 0;
        int[] sampleDiffs = new int[33];
        for (int x = xLo; x <= xHi; x++) {
            int ns = 0;
            for (int si = 0; si < 33; si++) {
                int y = Math.min(rh - 1, (int) Math.round(rh * (0.04 + 0.028 * si)));
                int c = rawBmp.getPixel(x, y);
                int r = (c >> 16) & 0xFF, b = c & 0xFF;
                sampleDiffs[ns++] = r - b;
            }
            Arrays.sort(sampleDiffs, 0, ns);
            if (sampleDiffs[ns / 2] > 22) {
                warmStreak++;
                if (warmStreak >= 4) {
                    foundWarmX = x - 3;
                    break;
                }
            } else {
                warmStreak = 0;
            }
        }
        int xStart = foundWarmX >= 0 ? foundWarmX : Math.max(rw / 3, rw - Math.round(rh * 0.588f));
        xStart = Math.min(rw - 180, Math.max(0, xStart));
        Bitmap bmp = Bitmap.createBitmap(rawBmp, xStart, 0, rw - xStart, rh);
        if (foundWarmX < 0) {
            VisionOutput probeHh = analyzeHeaderAndHandFrame(bmp, customPalette, allStages);
            if (probeHh.detectedStageNo < 1) {
                int altX = Math.min(rw - 180, Math.max(0, rw / 2));
                if (Math.abs(altX - xStart) > 12) {
                    Bitmap altBmp = Bitmap.createBitmap(rawBmp, altX, 0, rw - altX, rh);
                    VisionOutput altHh = analyzeHeaderAndHandFrame(altBmp, customPalette, allStages);
                    if (altHh.detectedStageNo >= 1) {
                        bmp.recycle();
                        bmp = altBmp;
                    } else {
                        altBmp.recycle();
                    }
                }
            }
        }
        Bitmap trimmed = trimEmulatorChromeBars(bmp);
        if (trimmed != bmp && bmp != rawBmp) {
            bmp.recycle();
        }
        return trimmed;
    }

    public static VisionOutput analyzeHeaderAndHandBitmap(
            Bitmap rawBmp, Map<Integer, int[]> customPalette, Map<Integer, StageCfg> allStages
    ) {
        return analyzeHeaderAndHandBitmap(rawBmp, customPalette, allStages, true);
    }

    public static VisionOutput analyzeHeaderAndHandBitmap(
            Bitmap rawBmp, Map<Integer, int[]> customPalette, Map<Integer, StageCfg> allStages, boolean allowStageDetect
    ) {
        Bitmap bmp = cropSplitScreenGamePane(rawBmp, customPalette, allStages);
        try {
            VisionOutput vo = analyzeHeaderAndHandFrame(bmp, customPalette, allStages);
            if (!allowStageDetect && vo != null) {
                vo.detectedStageNo = -1;
            }
            return vo;
        } finally {
            if (bmp != rawBmp) bmp.recycle();
        }
    }

    private static VisionOutput analyzeHeaderAndHandFrame(
            Bitmap bmp, Map<Integer, int[]> customPalette, Map<Integer, StageCfg> allStages
    ) {
        int origW = bmp.getWidth();
        int origH = bmp.getHeight();
        int workW = Math.min(540, origW);
        int workH = Math.max(180, Math.round((float) origH * workW / Math.max(1, origW)));
        Bitmap workBmp = (workW == origW && workH == origH) ? bmp : Bitmap.createScaledBitmap(bmp, workW, workH, true);
        int[] px = new int[workW * workH];
        workBmp.getPixels(px, 0, workW, 0, 0, workW, workH);
        if (workBmp != bmp) workBmp.recycle();
        return analyzeHeaderAndHandArgb(px, workW, workH, customPalette, allStages);
    }

    private static boolean[] detectThemeAndRedVignette(
            int[] px, int[] hsvH, int[] hsvV, int W, int H, int gameTopY, int gameBotY
    ) {
        int gameH = Math.max(200, gameBotY - gameTopY);
        int by0 = Math.max(0, gameTopY + (int) (0.24 * gameH));
        int by1 = Math.min(H, gameTopY + (int) (0.64 * gameH));
        int bw = Math.max(4, (int) (0.04 * W));
        List<Integer> edgeVs = new ArrayList<>(), edgeHs = new ArrayList<>();
        List<Integer> edgeRg = new ArrayList<>(), edgeRs = new ArrayList<>();
        for (int y = by0; y < by1; y += 2) {
            for (int x = 0; x < bw; x += 2) {
                int iL = y * W + x;
                int iR = y * W + (W - 1 - x);
                edgeVs.add(hsvV[iL]); edgeHs.add(hsvH[iL]);
                edgeVs.add(hsvV[iR]); edgeHs.add(hsvH[iR]);
                int rL = (px[iL] >> 16) & 0xFF, gL = (px[iL] >> 8) & 0xFF;
                int rR = (px[iR] >> 16) & 0xFF, gR = (px[iR] >> 8) & 0xFF;
                edgeRs.add(rL); edgeRg.add(rL - gL);
                edgeRs.add(rR); edgeRg.add(rR - gR);
            }
        }
        // Also sample interior valley between board bottom and hand tray (immune to red edge vignette!)
        int vy0 = Math.max(0, gameTopY + (int) (0.635 * gameH));
        int vy1 = Math.min(H, gameTopY + (int) (0.680 * gameH));
        List<Integer> valVs = new ArrayList<>(), valHs = new ArrayList<>();
        for (int y = vy0; y < vy1; y += 2) {
            for (int x = (int) (0.24 * W); x < (int) (0.76 * W); x += 4) {
                int idx = y * W + x;
                valVs.add(hsvV[idx]); valHs.add(hsvH[idx]);
            }
        }
        boolean redDanger = medianInt(edgeRs) >= 165 && medianInt(edgeRg) >= 92;
        int mEdgeV = medianInt(edgeVs), mEdgeH = medianInt(edgeHs);
        int mValV = valVs.isEmpty() ? mEdgeV : medianInt(valVs);
        int mValH = valHs.isEmpty() ? mEdgeH : medianInt(valHs);
        boolean warmByEdge = mEdgeV >= 118 && (mEdgeH <= 29 || mEdgeH >= 170);
        boolean warmByVal = mValV >= 120 && mValH >= 6 && mValH <= 29;
        return new boolean[]{warmByEdge || warmByVal, redDanger};
    }

    private static boolean isRedVignetteBorderPixel(
            int x, int y, int W, int gameTopY, int gameBotY, int rc, int gc, int bc, int hc
    ) {
        int gameH = Math.max(200, gameBotY - gameTopY);
        boolean inOuterBand = (x < (int) (0.088 * W)) || (x >= W - (int) (0.088 * W))
                || (y >= gameTopY + (int) (0.858 * gameH)) || (y < gameTopY + (int) (0.085 * gameH));
        if (!inOuterBand) return false;
        return (hc <= 15 || hc >= 173) && (rc - gc) >= 72 && gc >= bc + 10;
    }

    public static VisionOutput analyzeHeaderAndHandArgb(
            int[] px, int W, int H, Map<Integer, int[]> customPalette, Map<Integer, StageCfg> allStages
    ) {
        int nPix = W * H;
        int[] hsvH = new int[nPix];
        int[] hsvS = new int[nPix];
        int[] hsvV = new int[nPix];
        for (int i = 0; i < nPix; i++) {
            int c = px[i];
            int[] hsv = rgbToHsvOpenCv((c >> 16) & 0xFF, (c >> 8) & 0xFF, c & 0xFF);
            hsvH[i] = hsv[0]; hsvS[i] = hsv[1]; hsvV[i] = hsv[2];
        }

        int gameTopY = 0;
        for (int y = 0; y < (int) (0.20 * H); y++) {
            int darkRow = 0;
            for (int x = (int) (0.15 * W); x < (int) (0.85 * W); x += 4) {
                if (hsvV[y * W + x] < 52) darkRow++;
            }
            int tot = Math.max(1, ((int) (0.85 * W) - (int) (0.15 * W) + 3) / 4);
            if ((double) darkRow / tot >= 0.85) gameTopY = y + 1;
            else break;
        }
        int gameBotY = H;
        for (int y = H - 1; y >= (int) (0.78 * H); y--) {
            int darkRow = 0;
            for (int x = (int) (0.15 * W); x < (int) (0.85 * W); x += 4) {
                if (hsvV[y * W + x] < 52) darkRow++;
            }
            int tot = Math.max(1, ((int) (0.85 * W) - (int) (0.15 * W) + 3) / 4);
            if ((double) darkRow / tot >= 0.85) gameBotY = y;
            else break;
        }
        int gameH = Math.max(200, gameBotY - gameTopY);

        boolean[] themeAndDanger = detectThemeAndRedVignette(px, hsvH, hsvV, W, H, gameTopY, gameBotY);
        boolean isWarmTheme = themeAndDanger[0];
        boolean redDangerActive = themeAndDanger[1];

        // Build masks only for the bottom half of the screen [gameTopY + 0.54*gameH .. gameBotY]
        byte[] chipMask = new byte[nPix];
        byte[] tileMask = new byte[nPix];
        int maskY0 = Math.max(0, gameTopY + (int) (0.54 * gameH));
        for (int y = maskY0; y < gameBotY; y++) {
            int rowOff = y * W;
            for (int x = 0; x < W; x++) {
                int i = rowOff + x;
                int hc = hsvH[i], sc = hsvS[i], vc = hsvV[i];
                int rc = (px[i] >> 16) & 0xFF, gc = (px[i] >> 8) & 0xFF, bc = px[i] & 0xFF;
                if (redDangerActive && isRedVignetteBorderPixel(x, y, W, gameTopY, gameBotY, rc, gc, bc, hc)) {
                    continue;
                }
                if (isWarmTheme) {
                    if (((hc <= 8 || hc >= 32) && sc >= 98 && vc >= 86) ||
                        (hc > 8 && hc < 32 && sc >= 174 && vc >= 115 && (bc <= 74 || (rc - bc) >= 152))) {
                        chipMask[i] = 1;
                    } else if ((hc >= 15 && hc <= 26 && sc >= 70 && sc <= 116 && vc >= 155 && vc <= 208) ||
                               (hc >= 11 && hc <= 25 && sc >= 78 && sc <= 168 && vc >= 74 && vc <= 138)) {
                        tileMask[i] = 1;
                    }
                } else {
                    if (sc >= 96 && vc >= 115) chipMask[i] = 1;
                    else if (sc < 85 && vc >= 42 && vc <= 130) tileMask[i] = 1;
                }
            }
        }

        VisionOutput out = new VisionOutput();
        int[] hdr = detectHeaderStageAndScore(px, W, H, hsvS, hsvV, allStages);
        out.detectedStageNo = hdr[0];
        out.detectedScore = hdr[1];
        out.detectedTargetScore = hdr[2];
        int[] bst = detectBoosters(px, W, H, hsvH, hsvS, hsvV);
        out.boostHammer = bst[0];
        out.boostMove = bst[1];
        out.boostRefresh = bst[2];

        int[][] capProto = new int[9][3];
        int[][] sideProto = new int[9][3];
        double[] hueProto = new double[9];
        for (int c = 1; c <= 8; c++) {
            int[] ov = customPalette != null ? customPalette.get(c) : null;
            if (ov != null) {
                capProto[c] = new int[]{ov[0], ov[1], ov[2]};
                sideProto[c] = new int[]{(int) (ov[0] * 0.74), (int) (ov[1] * 0.70), (int) (ov[2] * 0.74)};
                hueProto[c] = rgbToHsvOpenCv(ov[0], ov[1], ov[2])[0];
            } else {
                capProto[c] = CHIP_CAP_RGB[c];
                sideProto[c] = CHIP_SIDE_RGB[c];
                hueProto[c] = CHIP_PROTO_HUE[c];
            }
        }

        // 1. Find upper clean background valley between board bottom and top of 3 hand stacks
        int xl0 = (int) (0.17 * W), xr0 = (int) (0.83 * W);
        int valSearch0 = Math.max(maskY0, gameTopY + (int) (0.58 * gameH));
        int valSearch1 = Math.min(gameBotY - 20, gameTopY + (int) (0.76 * gameH));
        int handY0 = gameTopY + (int) (0.665 * gameH);
        int quietUpper = 0;
        for (int y = valSearch0; y < valSearch1; y++) {
            int cnt = 0;
            for (int x = xl0; x < xr0; x++) {
                int idx = y * W + x;
                if (chipMask[idx] != 0 || tileMask[idx] != 0) cnt++;
            }
            if ((double) cnt / Math.max(1, xr0 - xl0) < 0.008) {
                quietUpper++;
                if (quietUpper == 4) {
                    handY0 = y - 2;
                }
            } else if (quietUpper >= 4) {
                // Reached the top of the hand stacks right after the board-to-hand valley!
                break;
            } else {
                quietUpper = 0;
            }
        }

        // 2. Find lower clean background valley right below the 3 hand stacks (above the 3 booster buttons)
        int scanEnd = Math.min(gameBotY - 2, Math.max(handY0 + (int) (0.20 * W), gameTopY + (int) (0.93 * gameH)));
        boolean seenHandPixels = false;
        int foundValleyY = -1;
        int quietLower = 0;
        for (int y = handY0 + 2; y < scanEnd; y++) {
            int cnt = 0;
            for (int x = xl0; x < xr0; x++) if (chipMask[y * W + x] != 0) cnt++;
            double frac = (double) cnt / Math.max(1, xr0 - xl0);
            if (frac >= 0.05) {
                seenHandPixels = true;
                quietLower = 0;
            } else if (seenHandPixels && frac < 0.008 && y >= handY0 + (int) (0.10 * W)) {
                quietLower++;
                if (quietLower >= 4) {
                    foundValleyY = y - 1;
                    break;
                }
            } else {
                quietLower = 0;
            }
        }
        int handY1 = foundValleyY > 0 ? foundValleyY : Math.min(gameBotY - 2, gameTopY + (int) Math.round(0.865 * gameH));

        double traySpanPx = (isWarmTheme ? 0.214 : 0.255) * W;
        double[] slotNomXs = {0.5 * W - traySpanPx, 0.5 * W, 0.5 * W + traySpanPx};
        boolean[] slotAct = new boolean[3];
        double[] slotSx = {slotNomXs[0], slotNomXs[1], slotNomXs[2]};
        double[] slotTopY = new double[3];
        double[] slotBotY = new double[3];
        List<Double> actBots = new ArrayList<>(), actWs = new ArrayList<>();
        double nomWHand = 0.1816 * W;

        // Segment horizontal X-columns in [handY0 .. handY1] with full support for 1, 2, or 3 centered/shifted stacks
        boolean[] anySegFoundBox = new boolean[1];
        int[][] slotWin = segmentAndAssignHandTraySlots(chipMask, W, handY0, handY1, traySpanPx, slotNomXs, anySegFoundBox);

        for (int i = 0; i < 3; i++) {
            int xl, xr;
            if (slotWin[i] != null) {
                xl = Math.max(0, slotWin[i][0] - 2);
                xr = Math.min(W, slotWin[i][1] + 3);
            } else if (!anySegFoundBox[0]) {
                int halfWin = Math.max(16, (int) Math.round(traySpanPx * 0.47));
                xl = Math.max(0, (int) Math.round(slotNomXs[i] - halfWin));
                xr = Math.min(W, (int) Math.round(slotNomXs[i] + halfWin));
            } else {
                // Other stack(s) were segmented cleanly; this slot is genuinely empty — never slice a shifted neighbor!
                continue;
            }
            int pixCnt = 0;
            int minXSlot = W, maxXSlot = -1;
            List<Integer> rowSpans = new ArrayList<>();
            for (int y = handY0; y < handY1; y++) {
                int rowCnt = 0;
                int rMinX = W, rMaxX = -1;
                for (int x = xl; x < xr; x++) {
                    if (chipMask[y * W + x] != 0) {
                        pixCnt++;
                        rowCnt++;
                        if (x < rMinX) rMinX = x;
                        if (x > rMaxX) rMaxX = x;
                    }
                }
                if (rowCnt >= 4) {
                    if (rMinX < minXSlot) minXSlot = rMinX;
                    if (rMaxX > maxXSlot) maxXSlot = rMaxX;
                    rowSpans.add(rMaxX - rMinX + 1);
                }
            }
            if (pixCnt < Math.max(25, (int) Math.pow(0.035 * W, 2)) || maxXSlot <= minXSlot) continue;
            double sx = 0.5 * (minXSlot + maxXSlot);
            double span98 = !rowSpans.isEmpty() ? percentileInt(rowSpans, 98.0) : (maxXSlot - minXSlot + 1.0);
            double wHand = Math.max(0.155 * W, Math.min(0.235 * W, span98));
            double hFlatHw = 0.257 * wHand;
            int fcL = Math.max(0, (int) Math.round(sx - hFlatHw * 0.74));
            int fcR = Math.min(W, (int) Math.round(sx + hFlatHw * 0.74));
            List<Integer> actR = new ArrayList<>();
            for (int y = handY0; y < handY1; y++) {
                int cnt = 0;
                for (int x = fcL; x < fcR; x++) if (chipMask[y * W + x] != 0) cnt++;
                if ((double) cnt / Math.max(1, fcR - fcL) >= 0.42) actR.add(y);
            }
            if (actR.size() < 6) continue;
            slotAct[i] = true;
            slotSx[i] = sx;
            slotTopY[i] = actR.get(0);
            slotBotY[i] = actR.get(actR.size() - 1) + 0.5;
            actBots.add(slotBotY[i]);
            actWs.add(wHand);
        }

        double sharedFloorY = !actBots.isEmpty() ? medianDouble(actBots) : (gameTopY + 0.824 * gameH);
        double sharedWHand = computeSharedHandWidth(slotAct, slotSx, actWs, W, traySpanPx, 0.155, 0.235, nomWHand);

        for (int i = 0; i < 3; i++) {
            int sid = i + 1;
            List<Layer> wl = new ArrayList<>();
            if (slotAct[i]) {
                double floorY = Math.abs(slotBotY[i] - sharedFloorY) <= 6.0 ? sharedFloorY : slotBotY[i];
                double pureCapH = 0.7415 * sharedWHand;
                double hSlice = 0.0556 * sharedWHand;
                double hRx = 0.50 * sharedWHand;
                double hFlatHw = 0.257 * sharedWHand;
                double hSlope = (0.405 * sharedWHand - 0.065 * sharedWHand) / Math.max(1.0, hRx - hFlatHw);

                int nChips = Math.max(1, Math.min(9, (int) Math.round((floorY - (slotTopY[i] + pureCapH)) / Math.max(1.2, hSlice))));
                int fcL = Math.max(0, (int) Math.round(slotSx[i] - hFlatHw * 0.74));
                int fcR = Math.min(W, (int) Math.round(slotSx[i] + hFlatHw * 0.74));
                int cy0H = Math.max(0, (int) Math.round(slotTopY[i] + 0.16 * pureCapH));
                int cy1H = Math.min(H, (int) Math.round(slotTopY[i] + 0.80 * pureCapH));
                List<Integer> cRs = new ArrayList<>(), cGs = new ArrayList<>(), cBs = new ArrayList<>();
                for (int y = cy0H; y < cy1H; y++) {
                    for (int x = fcL; x < fcR; x++) {
                        int idx = y * W + x;
                        if (chipMask[idx] != 0) {
                            int c = px[idx];
                            cRs.add((c >> 16) & 0xFF); cGs.add((c >> 8) & 0xFF); cBs.add(c & 0xFF);
                        }
                    }
                }
                int topCid = classifyChipPixels(cRs, cGs, cBs, capProto, sideProto, hueProto, true);
                if (topCid > 0) {
                    List<Integer> sliceCids = new ArrayList<>();
                    int dxLeft = Math.max(2, (int) Math.floor(hRx * 0.88));
                    int flatLim = Math.max(2, (int) Math.round(hFlatHw * 0.92));
                    double[] subOffs = {0.42, 0.56};
                    for (int k = 0; k < nChips - 1; k++) {
                        List<Integer> sRs = new ArrayList<>(), sGs = new ArrayList<>(), sBs = new ArrayList<>();
                        for (double subOff : subOffs) {
                            // Exclude right-hand shaded bevel (> +0.92*hFlatHw) so yellow Колос never shifts to orange Клён!
                            for (int dx = -dxLeft; dx <= flatLim; dx++) {
                                int pxi = (int) Math.round(slotSx[i] + dx);
                                if (pxi < 0 || pxi >= W) continue;
                                double yFl = floorY - Math.max(0.0, Math.abs(dx) - hFlatHw) * hSlope;
                                int pyi = (int) Math.round(yFl - (k + subOff) * hSlice);
                                if (pyi >= 0 && pyi < H && chipMask[pyi * W + pxi] != 0 && hsvV[pyi * W + pxi] >= 102) {
                                    int c = px[pyi * W + pxi];
                                    int r = (c >> 16) & 0xFF, g = (c >> 8) & 0xFF, b = c & 0xFF;
                                    sRs.add(r); sGs.add(g); sBs.add(b);
                                    if (Math.abs(dx) <= flatLim) {
                                        sRs.add(r); sGs.add(g); sBs.add(b);
                                    }
                                }
                            }
                        }
                        int scid = topCid;
                        if (sRs.size() >= 3) {
                            int cand = classifyChipPixels(sRs, sGs, sBs, capProto, sideProto, hueProto, false);
                            if (cand > 0) scid = cand;
                        } else if (!sliceCids.isEmpty()) {
                            scid = sliceCids.get(sliceCids.size() - 1);
                        }
                        sliceCids.add(scid);
                    }
                    sliceCids.add(topCid);

                    for (int cvId : sliceCids) {
                        if (!wl.isEmpty() && wl.get(wl.size() - 1).colour == cvId) {
                            Layer last = wl.get(wl.size() - 1);
                            last.count = Math.min(9, last.count + 1);
                        } else {
                            wl.add(new Layer(cvId, 1));
                        }
                    }
                }
            }
            out.waits.put(sid, wl);
        }
        return out;
    }

    /**
     * Accurately segments 1, 2, or 3 physical stacks in the hand tray [handY0 .. handY1],
     * handling horizontal shifting toward screen center when 1 or 2 cards remain!
     */
    private static int[][] segmentAndAssignHandTraySlots(
            byte[] chipMask, int W, int handY0, int handY1,
            double traySpanPx, double[] slotNomXs, boolean[] anySegFoundOut
    ) {
        int xScanMin = Math.max(4, (int) Math.round(0.140 * W));
        int xScanMax = Math.min(W - 4, (int) Math.round(0.860 * W));
        int[] colCnts = new int[W];
        for (int y = handY0; y < handY1; y++) {
            int ro = y * W;
            for (int x = xScanMin; x < xScanMax; x++) {
                if (chipMask[ro + x] != 0) colCnts[x]++;
            }
        }
        int minSegW = Math.max(12, (int) Math.round(0.105 * W));
        List<int[]> rawSegs = new ArrayList<>();
        int segStart = -1, gapRun = 0;
        for (int x = xScanMin; x < xScanMax; x++) {
            if (colCnts[x] >= 3) {
                if (segStart < 0) segStart = x;
                gapRun = 0;
            } else if (segStart >= 0) {
                gapRun++;
                if (gapRun > 2) {
                    int segEnd = x - gapRun;
                    if (segEnd - segStart + 1 >= minSegW) {
                        rawSegs.add(new int[]{segStart, segEnd});
                    }
                    segStart = -1;
                    gapRun = 0;
                }
            }
        }
        if (segStart >= 0) {
            int segEnd = (xScanMax - 1) - gapRun;
            if (segEnd - segStart + 1 >= minSegW) rawSegs.add(new int[]{segStart, segEnd});
        }

        // Split merged wide segments if glow/shadow bridged a narrow gap between 2 or 3 adjacent stacks
        List<int[]> segs = new ArrayList<>();
        for (int[] sg : rawSegs) {
            int sw = sg[1] - sg[0] + 1;
            if (sw >= (int) Math.round(0.50 * W)) {
                int m1 = findMinColIdx(colCnts, sg[0] + minSegW, sg[0] + (sw * 46) / 100);
                int m2 = findMinColIdx(colCnts, sg[0] + (sw * 54) / 100, sg[1] - minSegW);
                if (m1 > sg[0] && m2 > m1 + minSegW && m2 < sg[1]) {
                    segs.add(new int[]{sg[0], m1 - 1});
                    segs.add(new int[]{m1 + 1, m2 - 1});
                    segs.add(new int[]{m2 + 1, sg[1]});
                    continue;
                }
            }
            if (sw >= (int) Math.round(0.305 * W)) {
                int mid = findMinColIdx(colCnts, sg[0] + minSegW, sg[1] - minSegW);
                if (mid > sg[0] && mid < sg[1]) {
                    segs.add(new int[]{sg[0], mid - 1});
                    segs.add(new int[]{mid + 1, sg[1]});
                    continue;
                }
            }
            segs.add(sg);
        }

        // Reject any border-glow artifact whose center lies outside physical hand slot bounds [0.175W .. 0.825W]
        segs.removeIf(sg -> {
            double midX = 0.5 * (sg[0] + sg[1]);
            return midX < 0.175 * W || midX > 0.825 * W;
        });

        if (segs.size() > 3) {
            segs.sort((a, b) -> Integer.compare(sumColRange(colCnts, b[0], b[1]), sumColRange(colCnts, a[0], a[1])));
            segs = new ArrayList<>(segs.subList(0, 3));
            segs.sort(Comparator.comparingInt(a -> a[0]));
        }

        if (anySegFoundOut != null && anySegFoundOut.length > 0) {
            anySegFoundOut[0] = !segs.isEmpty();
        }

        int[][] slotWin = new int[3][];
        if (segs.size() == 3) {
            slotWin[0] = segs.get(0);
            slotWin[1] = segs.get(1);
            slotWin[2] = segs.get(2);
        } else if (segs.size() == 2) {
            double cx0 = 0.5 * (segs.get(0)[0] + segs.get(0)[1]);
            double cx1 = 0.5 * (segs.get(1)[0] + segs.get(1)[1]);
            double pairMid = 0.5 * (cx0 + cx1);
            // When 2 stacks remain in the game, they center symmetrically around 0.5*W (≈0.393W and ≈0.607W)
            // OR sit at outer #1 & #3 (0.286W & 0.714W). Map Left -> #1 (ЛЕВ) and Right -> #3 (ПРАВ)!
            if (Math.abs(pairMid - 0.5 * W) <= 0.068 * W) {
                slotWin[0] = segs.get(0);
                slotWin[2] = segs.get(1);
            } else if (pairMid < 0.5 * W) {
                slotWin[0] = segs.get(0);
                slotWin[1] = segs.get(1);
            } else {
                slotWin[1] = segs.get(0);
                slotWin[2] = segs.get(1);
            }
        } else if (segs.size() == 1) {
            double cx0 = 0.5 * (segs.get(0)[0] + segs.get(0)[1]);
            if (Math.abs(cx0 - 0.5 * W) <= 0.095 * W) {
                slotWin[1] = segs.get(0);
            } else if (cx0 < 0.5 * W) {
                slotWin[0] = segs.get(0);
            } else {
                slotWin[2] = segs.get(0);
            }
        }
        return slotWin;
    }

    private static int findMinColIdx(int[] colCnts, int fromX, int toX) {
        int lo = Math.max(2, fromX);
        int hi = Math.min(colCnts.length - 3, toX);
        if (lo > hi) return (lo + hi) / 2;
        int bestX = (lo + hi) / 2;
        int bestV = Integer.MAX_VALUE;
        for (int x = lo; x <= hi; x++) {
            if (colCnts[x] < bestV) {
                bestV = colCnts[x];
                bestX = x;
            }
        }
        return bestX;
    }

    private static int sumColRange(int[] colCnts, int a, int b) {
        int s = 0;
        for (int x = Math.max(0, a); x <= Math.min(colCnts.length - 1, b); x++) s += colCnts[x];
        return s;
    }

    private static double computeSharedHandWidth(
            boolean[] slotAct, double[] slotSx, List<Double> actWs,
            int W, double traySpanPx, double minFrac, double maxFrac, double nomWHand
    ) {
        if (actWs.size() == 3) {
            double pitch = 0.5 * (slotSx[2] - slotSx[0]);
            return Math.max(minFrac * W, Math.min(maxFrac * W, 0.8445 * pitch));
        } else if (actWs.size() == 2) {
            double xLeft = 1e9, xRight = -1e9;
            for (int i = 0; i < 3; i++) {
                if (slotAct[i]) {
                    if (slotSx[i] < xLeft) xLeft = slotSx[i];
                    if (slotSx[i] > xRight) xRight = slotSx[i];
                }
            }
            double rawDx = Math.max(1.0, xRight - xLeft);
            // If outer slots #1 & #3 without shift (dx ≈ 2*traySpanPx), divide by 2; if centered 2-card tray (dx ≈ 1*traySpanPx), keep 1x!
            double effPitch = (rawDx > 1.55 * traySpanPx) ? (0.5 * rawDx) : rawDx;
            double fromPitch = 0.8445 * effPitch;
            double fromSpan = medianDouble(actWs);
            double blended = (effPitch >= 0.72 * traySpanPx && effPitch <= 1.28 * traySpanPx)
                    ? (0.5 * fromPitch + 0.5 * fromSpan)
                    : fromSpan;
            return Math.max(minFrac * W, Math.min(maxFrac * W, blended));
        } else if (actWs.size() == 1) {
            return actWs.get(0);
        }
        return nomWHand;
    }

    public static VisionOutput analyzeScreenshotBitmap(
            Bitmap bmp, StageCfg stageCfg, int curScore, int rotationDeg,
            Calibration userCalib, Map<Integer, int[]> customPalette
    ) {
        return analyzeScreenshotBitmap(bmp, stageCfg, curScore, rotationDeg, userCalib, customPalette, sStages);
    }

    public static VisionOutput analyzeScreenshotBitmap(
            Bitmap rawBmp, StageCfg stageCfg, int curScore, int rotationDeg,
            Calibration userCalib, Map<Integer, int[]> customPalette, Map<Integer, StageCfg> allStages
    ) {
        Bitmap bmp = (userCalib == null) ? cropSplitScreenGamePane(rawBmp, customPalette, allStages) : rawBmp;
        int origW = bmp.getWidth();
        int origH = bmp.getHeight();
        int workW = Math.min(540, origW);
        int workH = Math.max(180, Math.round((float) origH * workW / Math.max(1, origW)));
        Bitmap workBmp = (workW == origW && workH == origH) ? bmp : Bitmap.createScaledBitmap(bmp, workW, workH, true);
        int[] px = new int[workW * workH];
        workBmp.getPixels(px, 0, workW, 0, 0, workW, workH);
        if (workBmp != bmp) workBmp.recycle();
        if (bmp != rawBmp) bmp.recycle();

        VisionOutput vo = analyzeScreenshotArgb(px, workW, workH, stageCfg, curScore, rotationDeg, userCalib, customPalette, allStages);
        if (vo.webCompatibleJson != null) {
            try {
                vo.webCompatibleJson.put("imageWidth", origW);
                vo.webCompatibleJson.put("imageHeight", origH);
            } catch (Exception ignored) {}
        }
        return vo;
    }

    public static VisionOutput analyzeScreenshotArgb(
            int[] px, int W, int H, StageCfg stageCfg, int curScore, int rotationDeg,
            Calibration userCalib, Map<Integer, int[]> customPalette, Map<Integer, StageCfg> allStages
    ) {
        int nPix = W * H;
        int[] hsvH = new int[nPix];
        int[] hsvS = new int[nPix];
        int[] hsvV = new int[nPix];
        for (int i = 0; i < nPix; i++) {
            int c = px[i];
            int[] hsv = rgbToHsvOpenCv((c >> 16) & 0xFF, (c >> 8) & 0xFF, c & 0xFF);
            hsvH[i] = hsv[0]; hsvS[i] = hsv[1]; hsvV[i] = hsv[2];
        }

        int gameTopY = 0;
        for (int y = 0; y < (int) (0.20 * H); y++) {
            int darkRow = 0;
            for (int x = (int) (0.15 * W); x < (int) (0.85 * W); x += 4) {
                if (hsvV[y * W + x] < 52) darkRow++;
            }
            int tot = Math.max(1, ((int) (0.85 * W) - (int) (0.15 * W) + 3) / 4);
            if ((double) darkRow / tot >= 0.85) gameTopY = y + 1;
            else break;
        }
        int gameBotY = H;
        for (int y = H - 1; y >= (int) (0.78 * H); y--) {
            int darkOrNav = 0;
            for (int x = (int) (0.15 * W); x < (int) (0.85 * W); x += 4) {
                if (hsvV[y * W + x] < 52) darkOrNav++;
            }
            int tot = Math.max(1, ((int) (0.85 * W) - (int) (0.15 * W) + 3) / 4);
            if ((double) darkOrNav / tot >= 0.85) gameBotY = y;
            else break;
        }

        boolean[] themeAndDanger = detectThemeAndRedVignette(px, hsvH, hsvV, W, H, gameTopY, gameBotY);
        boolean isWarmTheme = themeAndDanger[0];
        boolean redDangerActive = themeAndDanger[1];

        byte[] chipMask = new byte[nPix];
        byte[] emptyMask = new byte[nPix];
        byte[] lockedMask = new byte[nPix];
        for (int y = 0; y < H; y++) {
            int ro = y * W;
            for (int x = 0; x < W; x++) {
                int i = ro + x;
                int hc = hsvH[i], sc = hsvS[i], vc = hsvV[i];
                int rc = (px[i] >> 16) & 0xFF, gc = (px[i] >> 8) & 0xFF, bc = px[i] & 0xFF;
                if (redDangerActive && isRedVignetteBorderPixel(x, y, W, gameTopY, gameBotY, rc, gc, bc, hc)) {
                    continue;
                }
                if (isWarmTheme) {
                    if (((hc <= 8 || hc >= 32) && sc >= 98 && vc >= 86) ||
                        (hc > 8 && hc < 32 && sc >= 174 && vc >= 115 && (bc <= 74 || (rc - bc) >= 152))) {
                        chipMask[i] = 1;
                    } else if (hc >= 15 && hc <= 26 && sc >= 70 && sc <= 116 && vc >= 155 && vc <= 208) {
                        emptyMask[i] = 1;
                    } else if (hc >= 11 && hc <= 25 && sc >= 78 && sc <= 168 && vc >= 74 && vc <= 138) {
                        lockedMask[i] = 1;
                    }
                } else {
                    if (sc >= 96 && vc >= 115) chipMask[i] = 1;
                    else if (sc < 85 && vc >= 68 && vc <= 130) emptyMask[i] = 1;
                    else if (sc < 85 && vc >= 42 && vc < 68) lockedMask[i] = 1;
                }
            }
        }

        float[] gradMag = new float[nPix];
        for (int y = 1; y < H - 1; y++) {
            int rowOff = y * W;
            for (int x = 1; x < W - 1; x++) {
                int cl = px[rowOff + (x - 1)], cr = px[rowOff + (x + 1)];
                int cu = px[rowOff - W + x], cd = px[rowOff + W + x];
                int drx = ((cr >> 16) & 0xFF) - ((cl >> 16) & 0xFF);
                int dgx = ((cr >> 8) & 0xFF) - ((cl >> 8) & 0xFF);
                int dbx = (cr & 0xFF) - (cl & 0xFF);
                int dry = ((cd >> 16) & 0xFF) - ((cu >> 16) & 0xFF);
                int dgy = ((cd >> 8) & 0xFF) - ((cu >> 8) & 0xFF);
                int dby = (cd & 0xFF) - (cu & 0xFF);
                int g2 = drx * drx + dgx * dgx + dbx * dbx + dry * dry + dgy * dgy + dby * dby;
                gradMag[rowOff + x] = (float) (Math.sqrt(g2) * 0.5);
            }
        }

        int[] hdr = detectHeaderStageAndScore(px, W, H, hsvS, hsvV, allStages);
        int effScore = (hdr[1] >= 0 && userCalib == null
                && (curScore <= 0 || (hdr[1] >= curScore - 35 && hdr[1] <= curScore + 85)))
                ? hdr[1] : curScore;
        StageCfg effStageCfg = stageCfg;
        if (effStageCfg == null && hdr[0] >= 1 && allStages != null && allStages.containsKey(hdr[0])) {
            effStageCfg = allStages.get(hdr[0]);
        }

        int[][] capProto = new int[9][3];
        int[][] sideProto = new int[9][3];
        double[] hueProto = new double[9];
        for (int c = 1; c <= 8; c++) {
            int[] ov = customPalette != null ? customPalette.get(c) : null;
            if (ov != null) {
                capProto[c] = new int[]{ov[0], ov[1], ov[2]};
                sideProto[c] = new int[]{(int) (ov[0] * 0.74), (int) (ov[1] * 0.70), (int) (ov[2] * 0.74)};
                hueProto[c] = rgbToHsvOpenCv(ov[0], ov[1], ov[2])[0];
            } else {
                capProto[c] = CHIP_CAP_RGB[c];
                sideProto[c] = CHIP_SIDE_RGB[c];
                hueProto[c] = CHIP_PROTO_HUE[c];
            }
        }

        double minX = 1e9, maxX = -1e9, minY = 1e9, maxY = -1e9;
        Map<Integer, double[]> rawMap = new HashMap<>();
        for (StageCellCfg scItem : effStageCfg.cells) {
            double[] wp = cellWorldPos(scItem.cellId, rotationDeg);
            rawMap.put(scItem.cellId, wp);
            if (wp[0] < minX) minX = wp[0];
            if (wp[0] > maxX) maxX = wp[0];
            if (wp[1] < minY) minY = wp[1];
            if (wp[1] > maxY) maxY = wp[1];
        }
        double midX = 0.5 * (minX + maxX), midY = 0.5 * (minY + maxY);
        Map<Integer, double[]> centered = new HashMap<>();
        List<double[]> activePts = new ArrayList<>();
        for (StageCellCfg scItem : effStageCfg.cells) {
            double[] wp = rawMap.get(scItem.cellId);
            double[] cp = new double[]{wp[0] - midX, wp[1] - midY};
            centered.put(scItem.cellId, cp);
            activePts.add(cp);
        }

        double cx0, cy0, sc, sy;
        if (userCalib != null) {
            cx0 = userCalib.centerX * W;
            cy0 = userCalib.centerY * H;
            sc = userCalib.scaleX * W;
            sy = userCalib.scaleY * H;
        } else {
            double[] fit = fitBoardCamera(px, activePts, minX - midX, maxX - midX, maxY - midY, chipMask, emptyMask, lockedMask, W, H);
            cx0 = fit[0]; cy0 = fit[1]; sc = fit[2]; sy = fit[3];
        }

        double hexRx = 0.525 * sc;
        double flatHw = 0.270 * sc;
        double capHalfH = 0.435 * sc;
        double floorDy = 0.432 * sc;
        double bevelSlope = (floorDy - 0.070 * sc) / Math.max(1.0, hexRx - flatHw);
        double topBevelSlope = capHalfH / Math.max(1.0, hexRx - flatHw);
        double sliceStep = 0.0601 * sc;

        // Sub-pixel vertical camera lock (cy0) using unoccluded 3D chip skirt bottoms:
        // Lock badges and empty flat tiles differ in height from 3D skirts by ~4-5px (~1 sliceStep).
        // Any occupied stack with clean non-chip background immediately below its flat bottom face
        // anchors cy0 to ±0.5px across the whole board.
        Map<Integer, Double> localByMap = new HashMap<>();
        if (userCalib == null) {
            List<Double> cy0Ests = new ArrayList<>();
            Map<Integer, Double> rawBotExact = new HashMap<>();
            int winSearch = Math.max(7, (int) Math.round(0.11 * sc));
            for (StageCellCfg scItem : effStageCfg.cells) {
                int cid = scItem.cellId;
                double[] cp = centered.get(cid);
                double bx = cx0 + (cp[0] / 100.0) * sc;
                double byNom = cy0 + (cp[1] / 100.0) * sy;
                int fxL = Math.max(0, (int) Math.round(bx - flatHw * 0.62));
                int fxR = Math.min(W, (int) Math.round(bx + flatHw * 0.62));
                if (fxR - fxL < 4) continue;

                // Must have colored chip pixels in the cell body above nominal floor
                int bodyY0 = Math.max(0, (int) Math.round(byNom - 0.30 * sc));
                int bodyY1 = Math.min(H, (int) Math.round(byNom + 0.15 * sc));
                int bodyTot = 0, bodyChp = 0;
                for (int y = bodyY0; y < bodyY1; y++) {
                    for (int x = fxL; x < fxR; x++) {
                        bodyTot++;
                        if (chipMask[y * W + x] != 0) bodyChp++;
                    }
                }
                if (bodyTot == 0 || (double) bodyChp / bodyTot < 0.38) continue;

                int nomBotY = (int) Math.round(byNom + floorDy);
                int sy0 = Math.max(2, nomBotY - winSearch);
                int sy1 = Math.min(H - 6, nomBotY + winSearch);
                int foundEdgeY = -1;
                for (int y = sy1; y >= sy0; y--) {
                    int cOn = 0;
                    for (int x = fxL; x < fxR; x++) {
                        if (chipMask[y * W + x] != 0) cOn++;
                    }
                    double fracOn = (double) cOn / (fxR - fxL);
                    if (fracOn >= 0.50) {
                        // Verify at least 4 rows right below y are clean non-chip (unoccluded skirt bottom!)
                        boolean cleanBelow = true;
                        for (int dy = 1; dy <= 4; dy++) {
                            int cBel = 0;
                            for (int x = fxL; x < fxR; x++) {
                                if (chipMask[(y + dy) * W + x] != 0) cBel++;
                            }
                            if ((double) cBel / (fxR - fxL) > 0.12) {
                                cleanBelow = false;
                                break;
                            }
                        }
                        if (cleanBelow) {
                            foundEdgeY = y;
                        }
                        break;
                    }
                }
                if (foundEdgeY > 0) {
                    double botExact = foundEdgeY + 0.5;
                    rawBotExact.put(cid, botExact);
                    cy0Ests.add(botExact - floorDy - (cp[1] / 100.0) * sy);
                }
            }
            if (!cy0Ests.isEmpty()) {
                cy0 = medianDouble(cy0Ests);
                for (Map.Entry<Integer, Double> e : rawBotExact.entrySet()) {
                    int cid = e.getKey();
                    double predBy = cy0 + (centered.get(cid)[1] / 100.0) * sy;
                    double locBy = e.getValue() - floorDy;
                    if (Math.abs(locBy - predBy) <= 0.95 * sliceStep) {
                        localByMap.put(cid, locBy);
                    }
                }
            }
        }

        VisionOutput out = new VisionOutput();
        out.detectedStageNo = effStageCfg.stageNo;
        out.detectedScore = effScore;
        out.detectedTargetScore = effStageCfg.targetScore;
        out.cells = createStageBoard(effStageCfg, effScore);

        byte[] claimed = new byte[nPix];
        List<StageCellCfg> sortedCells = new ArrayList<>(effStageCfg.cells);
        Collections.sort(sortedCells, (a, b) -> Double.compare(centered.get(b.cellId)[1], centered.get(a.cellId)[1]));

        Map<Integer, double[]> samplePts = new HashMap<>();

        for (StageCellCfg scItem : sortedCells) {
            int cid = scItem.cellId;
            double[] cp = centered.get(cid);
            double bx = cx0 + (cp[0] / 100.0) * sc;
            double by = localByMap.containsKey(cid) ? localByMap.get(cid) : (cy0 + (cp[1] / 100.0) * sy);
            CellState cs = out.cells[cid];
            samplePts.put(cid, new double[]{bx, by});

            int x0 = Math.max(0, (int) Math.round(bx - 0.28 * sc)), x1 = Math.min(W, (int) Math.round(bx + 0.28 * sc));
            int y0 = Math.max(0, (int) Math.round(by - 0.34 * sc)), y1 = Math.min(H, (int) Math.round(by + 0.10 * sc));
            int yUpSplit = (int) Math.round(by - 0.12 * sc);

            // Sample compact 5x5 cell appearance patch for multi-frame visual stability comparison
            int[] thumb = new int[25];
            for (int ty = 0; ty < 5; ty++) {
                int pyS = Math.max(0, Math.min(H - 1, (int) Math.round(by - (0.30 - 0.08 * ty) * sc)));
                for (int tx = 0; tx < 5; tx++) {
                    int pxS = Math.max(0, Math.min(W - 1, (int) Math.round(bx + (-0.20 + 0.10 * tx) * sc)));
                    thumb[ty * 5 + tx] = px[pyS * W + pxS] & 0x00FFFFFF;
                }
            }
            out.cellThumbRgb[cid] = thumb;

            int unclCnt = 0, empCnt = 0, lckCnt = 0, chpCnt = 0;
            int upUncl = 0, upEmp = 0, upChp = 0;
            int totPopArea = 0, darkPopupCnt = 0;
            int yPop0 = Math.max(0, (int) Math.round(by - 0.78 * sc));
            for (int y = yPop0; y < y1; y += 2) {
                for (int x = x0; x < x1; x += 2) {
                    int idxP = y * W + x;
                    totPopArea++;
                    if (isWarmTheme && hsvV[idxP] < 58 && hsvS[idxP] < 72) darkPopupCnt++;
                }
            }
            for (int y = y0; y < y1; y++) {
                for (int x = x0; x < x1; x++) {
                    int idx = y * W + x;
                    if (claimed[idx] == 0) {
                        unclCnt++;
                        if (emptyMask[idx] != 0) empCnt++;
                        if (lockedMask[idx] != 0) lckCnt++;
                        if (chipMask[idx] != 0) chpCnt++;
                        if (y < yUpSplit) {
                            upUncl++;
                            if (emptyMask[idx] != 0) upEmp++;
                            if (chipMask[idx] != 0) upChp++;
                        }
                    }
                }
            }
            if (totPopArea >= 12 && (double) darkPopupCnt / totPopArea >= 0.28) {
                out.popupCovered[cid] = true;
            }
            double empRatio = unclCnt > 0 ? (double) empCnt / unclCnt : 0.0;
            double lckRatio = unclCnt > 0 ? (double) lckCnt / unclCnt : 0.0;
            double chpRatio = unclCnt > 0 ? (double) chpCnt / unclCnt : 0.0;
            double upEmpRatio = upUncl > 0 ? (double) upEmp / upUncl : 0.0;
            double upChpRatio = upUncl > 0 ? (double) upChp / upUncl : 0.0;

            // Locked Rainbow Tower (lockCardScore > 0, e.g. Stage 11 #11 [🔒300] & #24 [🔒450]):
            // Its exact layers (8..12 chips) are deterministically known while locked, AND its tall 3D body
            // MUST be claimed in `claimed[]` so cells behind it (#10, #23) never eat its top cap!
            if (cs.lockCardScore > 0) {
                cs.layers.clear();
                for (Layer l : scItem.initLayers) cs.layers.add(l.copy());
                int kLockTower = Math.max(1, Math.min(13, sumLayerBlocks(cs.layers)));
                double lockCapCy = by - kLockTower * sliceStep;
                double lockTopY = lockCapCy - capHalfH;
                out.measuredHeightSlices[cid] = (float) kLockTower;
                samplePts.put(cid, new double[]{bx, lockCapCy});
                double padTopLock = Math.max(3.0, 0.55 * sliceStep);
                fillPolygonMask(claimed, W, H, new double[][]{
                        {bx - flatHw - 2.0, lockTopY - padTopLock},
                        {bx + flatHw + 2.0, lockTopY - padTopLock},
                        {bx + hexRx + 2.0, lockCapCy - 2.0},
                        {bx + hexRx + 2.0, by + floorDy - (hexRx - flatHw) * bevelSlope + 2.0},
                        {bx + flatHw + 2.0, by + floorDy + 2.0},
                        {bx - flatHw - 2.0, by + floorDy + 2.0},
                        {bx - hexRx - 2.0, by + floorDy - (hexRx - flatHw) * bevelSlope + 2.0},
                        {bx - hexRx - 2.0, lockCapCy - 2.0}
                });
                continue;
            }
            if (cs.lockScore > 0 && cs.lockCardScore == 0 && chpRatio >= 0.34 && lckRatio < 0.14) {
                // Empty-lock cell visibly holds colored chips (not a brown padlock), so it is unlocked in the real game!
                cs.lockScore = 0;
            }
            if (cs.lockScore > 0 || (lckRatio > 0.22 && chpRatio < 0.20)) {
                cs.layers.clear();
                if (cs.lockScore == 0 && scItem.unlockCellScore > 0) cs.lockScore = scItem.unlockCellScore;
                continue;
            }
            // Even when a tall front tower claims the lower half of an empty hex, its upper beige floor stays exposed!
            if ((empRatio > 0.35 && chpRatio < 0.24)
                    || (upUncl >= 14 && upEmpRatio > 0.30 && upChpRatio < 0.20)) {
                cs.layers.clear();
                continue;
            }

            // Check cid's own body/cap zone (up to k=9 cap, never reaching k=13..15 into cid-1 behind it!)
            int cxL = Math.max(0, (int) Math.round(bx - flatHw * 0.75)), cxR = Math.min(W, (int) Math.round(bx + flatHw * 0.75));
            int yChk0 = Math.max(0, (int) Math.round(by - 9 * sliceStep - capHalfH)), yChk1 = Math.min(H, (int) Math.round(by + 0.25 * sc));
            int bandUncl = 0, bandChp = 0;
            for (int y = yChk0; y < yChk1; y++) {
                for (int x = cxL; x < cxR; x++) {
                    int idx = y * W + x;
                    if (claimed[idx] == 0) {
                        bandUncl++;
                        if (chipMask[idx] != 0) bandChp++;
                    }
                }
            }
            if (bandUncl < 10 || (double) bandChp / bandUncl < 0.12) {
                cs.layers.clear();
                continue;
            }

            // Detect if an existing cell sits directly behind/above cid in the same column (dy ≈ sy ≈ 12.6 * sliceStep)
            boolean hasDirectBackCell = false;
            int kBackStart = 12;
            for (StageCellCfg other : effStageCfg.cells) {
                if (other.cellId == cid) continue;
                double[] ocp = centered.get(other.cellId);
                double obx = cx0 + (ocp[0] / 100.0) * sc;
                double oby = localByMap.containsKey(other.cellId) ? localByMap.get(other.cellId) : (cy0 + (ocp[1] / 100.0) * sy);
                double dyBack = by - oby;
                if (Math.abs(obx - bx) < 0.24 * sc && dyBack > 0.50 * sc && dyBack < 1.08 * sc) {
                    hasDirectBackCell = true;
                    int estK = (int) Math.floor(dyBack / Math.max(1.0, sliceStep)) - 2;
                    if (estK < kBackStart) kBackStart = Math.max(8, estK);
                }
            }

            // 6-edge discrete k = 1..12 hexagon cap matcher (with spine continuity + back-cell cap-steal guard!)
            int bestK = 1;
            double bestKScore = -1e18;
            double bestTopY = by - capHalfH - sliceStep;
            int bestLowK = 1;
            double bestLowKScore = -1e18;
            double bestLowTopY = bestTopY;
            int dxMax = Math.max(2, (int) Math.floor(hexRx) - 3);
            int spL = Math.max(0, (int) Math.round(bx - flatHw * 0.55));
            int spR = Math.min(W, (int) Math.round(bx + flatHw * 0.55));
            for (int k = 1; k <= 12; k++) {
                double capCyK = by - k * sliceStep;
                double capTopK = capCyK - capHalfH;
                if (capTopK < 3) break;
                // Unclaimed spine continuity check: never jump across a genuine >=6px empty/background gap onto a back tower,
                // while remaining 100% immune to 1-2px anti-aliased inter-layer color seams (e.g. Орёл -> Индейка on #2)!
                if (k >= 5 && spR - spL >= 4) {
                    int ySpine = (int) Math.round(by + floorDy - (k - 1.5) * sliceStep);
                    if (ySpine >= 5 && ySpine < H - 5) {
                        double maxSolidFrac = 0.0;
                        int maxUnclRow = 0;
                        for (int dyS : new int[]{-3, 0, 3}) {
                            int rUncl = 0, rSolid = 0;
                            int rowOff = (ySpine + dyS) * W;
                            for (int x = spL; x < spR; x++) {
                                int sIdx = rowOff + x;
                                if (claimed[sIdx] == 0) {
                                    rUncl++;
                                    if (chipMask[sIdx] != 0 || lockedMask[sIdx] != 0) rSolid++;
                                }
                            }
                            if (rUncl > maxUnclRow) maxUnclRow = rUncl;
                            if (rUncl >= 6) {
                                double f = (double) rSolid / rUncl;
                                if (f > maxSolidFrac) maxSolidFrac = f;
                            }
                        }
                        if (maxUnclRow >= 6 && maxSolidFrac < 0.25) break;
                    }
                }
                List<Integer> inR = new ArrayList<>(), inG = new ArrayList<>(), inB = new ArrayList<>();
                List<Integer> abvR = new ArrayList<>(), abvG = new ArrayList<>(), abvB = new ArrayList<>();
                List<Integer> lowR = new ArrayList<>(), lowG = new ArrayList<>(), lowB = new ArrayList<>();
                double edgeGradSum = 0.0, abvSum = 0.0;
                int edgeCnt = 0, unclIn = 0, totIn = 0, rimIn = 0, rimTot = 0, sameAboveIn = 0, abvTot = 0;
                for (int dx = -dxMax; dx <= dxMax; dx += 2) {
                    int pxi = (int) Math.round(bx + dx);
                    if (pxi < 2 || pxi >= W - 2) continue;
                    int ye = (int) Math.round(capTopK + Math.max(0.0, Math.abs(dx) - flatHw) * topBevelSlope);
                    if (ye >= 2 && ye < H - 4) {
                        float gLoc = Math.max(gradMag[(ye - 1) * W + pxi], Math.max(gradMag[ye * W + pxi], gradMag[(ye + 1) * W + pxi]));
                        edgeGradSum += gLoc;
                        edgeCnt++;
                        // Immediate rim check (2..4px below ye): rejects k = K_true + 1 floating 1 sliceStep above cap!
                        // Include hsvV >= 212 so bright specular bevel highlights on orange Клён caps count as valid rim!
                        if (Math.abs(dx) <= flatHw + 2) {
                            int idxR1 = Math.min(H - 1, ye + 2) * W + pxi;
                            int idxR2 = Math.min(H - 1, ye + Math.max(4, (int) Math.round(0.75 * sliceStep))) * W + pxi;
                            rimTot += 2;
                            if (claimed[idxR1] == 0 && (chipMask[idxR1] != 0 || hsvV[idxR1] >= 212)) rimIn++;
                            if (claimed[idxR2] == 0 && (chipMask[idxR2] != 0 || hsvV[idxR2] >= 212)) rimIn++;

                            int yAbv = Math.max(0, ye - Math.max(5, (int) Math.round(1.05 * sliceStep)));
                            int idxA = yAbv * W + pxi;
                            abvTot++;
                            if (claimed[idxA] == 0 && chipMask[idxA] != 0) {
                                sameAboveIn++;
                                int cA = px[idxA];
                                abvR.add((cA >> 16) & 0xFF); abvG.add((cA >> 8) & 0xFF); abvB.add(cA & 0xFF);
                            }
                        }
                        int[] yis = {
                                Math.min(H - 1, ye + 2),
                                Math.min(H - 1, ye + Math.max(4, (int) Math.round(0.11 * sc))),
                                Math.min(H - 1, ye + Math.max(7, (int) Math.round(0.26 * sc)))
                        };
                        for (int yi : yis) {
                            totIn++;
                            int idx = yi * W + pxi;
                            if (claimed[idx] == 0 && chipMask[idx] != 0) {
                                unclIn++;
                                int c = px[idx];
                                inR.add((c >> 16) & 0xFF); inG.add((c >> 8) & 0xFF); inB.add(c & 0xFF);
                            }
                        }
                        // Lower-half cap sample to prevent jumping onto an upper neighbor's hanging skirt (#2 above #3)
                        if (Math.abs(dx) <= flatHw) {
                            int yLow = Math.min(H - 1, (int) Math.round(capCyK + 0.35 * capHalfH));
                            int idxL = yLow * W + pxi;
                            if (claimed[idxL] == 0 && chipMask[idxL] != 0) {
                                int cL = px[idxL];
                                lowR.add((cL >> 16) & 0xFF); lowG.add((cL >> 8) & 0xFF); lowB.add(cL & 0xFF);
                            }
                        }
                        int ca = px[Math.max(0, ye - 4) * W + pxi], cb = px[Math.min(H - 1, ye + 4) * W + pxi];
                        abvSum += Math.sqrt(
                                Math.pow(((cb >> 16) & 0xFF) - ((ca >> 16) & 0xFF), 2) +
                                Math.pow(((cb >> 8) & 0xFF) - ((ca >> 8) & 0xFF), 2) +
                                Math.pow((cb & 0xFF) - (ca & 0xFF), 2)
                        );
                    }
                }
                if (totIn == 0 || (double) unclIn / totIn < 0.50 || inR.size() < 8) continue;
                if (rimTot > 0 && (double) rimIn / rimTot < 0.45) continue;
                int upCid = classifyChipPixels(inR, inG, inB, capProto, sideProto, hueProto, true);
                if (k >= 3 && lowR.size() >= 4) {
                    int loCid = classifyChipPixels(lowR, lowG, lowB, capProto, sideProto, hueProto, true);
                    // Only reject genuinely distinct color families (never maple-leaf embossing shadow 2<->1 or 3<->2!)
                    boolean sameFamily = (upCid == loCid)
                            || (upCid == 2 && (loCid == 1 || loCid == 3))
                            || (upCid == 3 && (loCid == 2 || loCid == 4))
                            || (upCid == 4 && loCid == 3)
                            || (upCid == 1 && (loCid == 2 || loCid == 8))
                            || (upCid == 8 && (loCid == 1 || loCid == 7))
                            || (upCid == 7 && (loCid == 6 || loCid == 8))
                            || (upCid == 6 && (loCid == 5 || loCid == 7))
                            || (upCid == 5 && loCid == 6);
                    if (upCid > 0 && loCid > 0 && !sameFamily) continue;
                }
                // Penalize internal embossed cap-icon lines where pixels ~1 sliceStep ABOVE ye are still the exact same cap color
                double insideCapPenalty = 0.0;
                if (abvTot > 0 && (double) sameAboveIn / abvTot >= 0.65 && abvR.size() >= 4) {
                    int aboveCid = classifyChipPixels(abvR, abvG, abvB, capProto, sideProto, hueProto, true);
                    if (aboveCid == upCid) insideCapPenalty = 55.0;
                }
                int mr = medianInt(inR), mg = medianInt(inG), mb = medianInt(inB);
                double mad = 0.0;
                for (int i = 0; i < inR.size(); i++) {
                    mad += Math.sqrt(Math.pow(inR.get(i) - mr, 2) + Math.pow(inG.get(i) - mg, 2) + Math.pow(inB.get(i) - mb, 2));
                }
                mad /= inR.size();
                double meanGrad = edgeCnt > 0 ? edgeGradSum / edgeCnt : 0.0;
                double meanAbv = edgeCnt > 0 ? abvSum / edgeCnt : 0.0;
                double rimFrac = rimTot > 0 ? (double) rimIn / rimTot : 0.5;
                double kScore = 2.0 * meanGrad + 1.35 * meanAbv + 72.0 * ((double) unclIn / totIn) + 28.0 * rimFrac - 2.9 * mad + Math.min(k, 7) * 0.45 - insideCapPenalty;
                if (kScore > bestKScore) {
                    bestKScore = kScore;
                    bestK = k;
                    bestTopY = capTopK;
                }
                if (k < kBackStart && kScore > bestLowKScore) {
                    bestLowKScore = kScore;
                    bestLowK = k;
                    bestLowTopY = capTopK;
                }
            }

            // Prevent front cell (#16, #4) from skipping its own valid lower cap peak onto the back neighbor's cap (#15, #3)!
            if (hasDirectBackCell && bestK >= kBackStart && bestLowKScore > -1e9 && bestLowKScore >= bestKScore - 26.0) {
                bestK = bestLowK;
                bestTopY = bestLowTopY;
            }

            double capCy = bestTopY + capHalfH;
            out.measuredHeightSlices[cid] = (float) Math.max(1.0, (by - capCy) / Math.max(1.0, sliceStep));
            int cy0Cap = Math.max(0, (int) Math.round(bestTopY + 0.20 * capHalfH));
            int cy1Cap = Math.min(H, (int) Math.round(bestTopY + 1.35 * capHalfH));
            List<Integer> cRs = new ArrayList<>(), cGs = new ArrayList<>(), cBs = new ArrayList<>();
            for (int y = cy0Cap; y < cy1Cap; y++) {
                for (int x = cxL; x < cxR; x++) {
                    int idx = y * W + x;
                    if (claimed[idx] == 0 && chipMask[idx] != 0) {
                        int c = px[idx];
                        cRs.add((c >> 16) & 0xFF); cGs.add((c >> 8) & 0xFF); cBs.add(c & 0xFF);
                    }
                }
            }
            int topCid = classifyChipPixels(cRs, cGs, cBs, capProto, sideProto, hueProto, true);
            if (topCid == 0) {
                cs.layers.clear();
                continue;
            }

            // Groove-immune 3D side-slice sampling:
            // Exclude dark right bevel (> +0.88*flatHw), pick local brightest ridge pixel per dx inside slice k
            // (skipping dark inter-chip seams V<102), and double-weight flat front face.
            List<Integer> sliceCids = new ArrayList<>();
            List<Integer> sliceMedVs = new ArrayList<>();
            int dxLeft = -Math.max(2, (int) Math.round(0.86 * hexRx));
            int flatLim = Math.max(2, (int) Math.round(0.88 * flatHw));
            double[] subRegular = {0.36, 0.52, 0.68};
            double[] subBottom = {0.48, 0.62, 0.76};
            for (int k = 0; k < bestK - 1; k++) {
                List<Integer> sRs = new ArrayList<>(), sGs = new ArrayList<>(), sBs = new ArrayList<>(), sVs = new ArrayList<>();
                double[] subs = (k == 0) ? subBottom : subRegular;
                for (int dx = dxLeft; dx <= flatLim; dx++) {
                    int pxi = (int) Math.round(bx + dx);
                    if (pxi < 0 || pxi >= W) continue;
                    double yFl = (by + floorDy) - Math.max(0.0, Math.abs(dx) - flatHw) * bevelSlope;
                    int bestIdx = -1, bestV = -1;
                    for (double sub : subs) {
                        int pyi = (int) Math.round(yFl - (k + sub) * sliceStep);
                        if (pyi >= 0 && pyi < H) {
                            int idx = pyi * W + pxi;
                            if (claimed[idx] == 0 && chipMask[idx] != 0 && hsvV[idx] > bestV) {
                                bestV = hsvV[idx];
                                bestIdx = idx;
                            }
                        }
                    }
                    if (bestIdx >= 0 && bestV >= 102) {
                        int c = px[bestIdx];
                        int r = (c >> 16) & 0xFF, g = (c >> 8) & 0xFF, b = c & 0xFF;
                        sRs.add(r); sGs.add(g); sBs.add(b); sVs.add(bestV);
                        if (Math.abs(dx) <= flatLim) {
                            sRs.add(r); sGs.add(g); sBs.add(b);
                        }
                    }
                }
                int scid = -1;
                int medV = 180;
                int minNeed = (k == 0) ? 5 : 3;
                if (sRs.size() >= minNeed) {
                    int cand = classifyChipPixels(sRs, sGs, sBs, capProto, sideProto, hueProto, false);
                    if (cand > 0) scid = cand;
                    medV = medianInt(sVs);
                } else if (!sliceCids.isEmpty() && sliceCids.get(sliceCids.size() - 1) > 0) {
                    scid = sliceCids.get(sliceCids.size() - 1);
                    medV = sliceMedVs.get(sliceMedVs.size() - 1);
                }
                sliceCids.add(scid);
                sliceMedVs.add(medV);
            }
            sliceCids.add(topCid);
            sliceMedVs.add(210);
            // Backfill any leading unknown bottom slices (scid == -1) from the nearest known slice above (never topCid!)
            for (int k = sliceCids.size() - 2; k >= 0; k--) {
                if (sliceCids.get(k) <= 0) {
                    sliceCids.set(k, sliceCids.get(k + 1));
                    sliceMedVs.set(k, sliceMedVs.get(k + 1));
                }
            }

            // Despike 1-slice inter-chip groove artifacts inside uniform runs (C -> shadowX×1 -> C)
            for (int k = 1; k < sliceCids.size() - 1; k++) {
                int prevC = sliceCids.get(k - 1), curC = sliceCids.get(k), nextC = sliceCids.get(k + 1);
                if (prevC == nextC && curC != prevC) {
                    boolean shadowAlias =
                            (prevC == 3 && (curC == 2 || curC == 1 || curC == 8 || curC == 6)) ||
                            (prevC == 2 && (curC == 1 || curC == 8)) ||
                            (prevC == 5 && (curC == 6 || curC == 1)) ||
                            (prevC == 6 && (curC == 7)) ||
                            (prevC == 4 && (curC == 3 || curC == 2));
                    int refV = Math.min(sliceMedVs.get(k - 1), sliceMedVs.get(k + 1));
                    if (shadowAlias || sliceMedVs.get(k) < (int) (0.88 * refV)) {
                        sliceCids.set(k, prevC);
                    }
                }
            }

            cs.layers.clear();
            for (int cvId : sliceCids) {
                if (!cs.layers.isEmpty() && cs.layers.get(cs.layers.size() - 1).colour == cvId) {
                    Layer last = cs.layers.get(cs.layers.size() - 1);
                    last.count = Math.min(9, last.count + 1);
                } else {
                    cs.layers.add(new Layer(cvId, 1));
                }
            }
            samplePts.put(cid, new double[]{bx, capCy});

            double padTop = Math.max(2.5, 0.45 * sliceStep);
            fillPolygonMask(claimed, W, H, new double[][]{
                    {bx - flatHw - 1.5, bestTopY - padTop},
                    {bx + flatHw + 1.5, bestTopY - padTop},
                    {bx + hexRx + 1.5, capCy - 1.5},
                    {bx + hexRx + 1.5, by + floorDy - (hexRx - flatHw) * bevelSlope + 1.5},
                    {bx + flatHw + 1.5, by + floorDy + 1.5},
                    {bx - flatHw - 1.5, by + floorDy + 1.5},
                    {bx - hexRx - 1.5, by + floorDy - (hexRx - flatHw) * bevelSlope + 1.5},
                    {bx - hexRx - 1.5, capCy - 1.5}
            });
        }

        // Decode 3 Hand Slots (`waits` 1..3) — aspect-ratio independent + exact physical cap/skirt geometry
        double maxBoardBotY = cy0 + ((maxY - midY) / 100.0) * sy + floorDy;
        int handY0, handY1;
        double traySpanPx;
        if (userCalib != null) {
            double nomTy = userCalib.trayY * H;
            traySpanPx = userCalib.traySpanX * W;
            handY0 = Math.max((int) Math.round(maxBoardBotY + 4), (int) Math.round(nomTy - 0.10 * H));
            handY1 = Math.min(gameBotY - 2, (int) Math.round(nomTy + 0.08 * H));
        } else {
            handY0 = Math.max((int) Math.round(maxBoardBotY + 6), (int) Math.round(0.62 * gameBotY));
            int xl0 = (int) (0.17 * W), xr0 = (int) (0.83 * W);
            // Locate the horizontal valley of clean background right BELOW the 3 hand stacks (above the 3 booster buttons)
            int scanEnd = Math.min(gameBotY - 2, Math.max(handY0 + (int) (0.20 * W), (int) (0.93 * gameBotY)));
            boolean seenHandPixels = false;
            int foundValleyY = -1;
            int quietRun = 0;
            for (int y = handY0 + 4; y < scanEnd; y++) {
                int cnt = 0;
                for (int x = xl0; x < xr0; x++) if (chipMask[y * W + x] != 0) cnt++;
                double frac = (double) cnt / Math.max(1, xr0 - xl0);
                if (frac >= 0.05) {
                    seenHandPixels = true;
                    quietRun = 0;
                } else if (seenHandPixels && frac < 0.008 && y >= handY0 + (int) (0.11 * W)) {
                    quietRun++;
                    if (quietRun >= 4) {
                        foundValleyY = y - 1;
                        break;
                    }
                } else {
                    quietRun = 0;
                }
            }
            handY1 = foundValleyY > 0 ? foundValleyY : Math.min(gameBotY - 2, (int) Math.round(0.865 * gameBotY));
            traySpanPx = (isWarmTheme ? 0.214 : 0.255) * W;
        }

        double[] slotNomXs = {0.5 * W - traySpanPx, 0.5 * W, 0.5 * W + traySpanPx};
        boolean[] slotAct = new boolean[3];
        double[] slotSx = new double[3];
        double[] slotW = new double[3];
        double[] slotTopY = new double[3];
        double[] slotBotY = new double[3];
        List<Double> actBots = new ArrayList<>(), actWs = new ArrayList<>();
        double nomWHand = 0.1816 * W;

        boolean[] anySegFoundFull = new boolean[1];
        int[][] slotWinFull = (userCalib == null)
                ? segmentAndAssignHandTraySlots(chipMask, W, handY0, handY1, traySpanPx, slotNomXs, anySegFoundFull)
                : new int[3][];

        for (int i = 0; i < 3; i++) {
            slotSx[i] = slotNomXs[i];
            int xl, xr;
            if (slotWinFull[i] != null) {
                xl = Math.max(0, slotWinFull[i][0] - 2);
                xr = Math.min(W, slotWinFull[i][1] + 3);
            } else if (!anySegFoundFull[0]) {
                int halfWin = Math.max(16, (int) Math.round(traySpanPx * 0.47));
                xl = Math.max(0, (int) Math.round(slotNomXs[i] - halfWin));
                xr = Math.min(W, (int) Math.round(slotNomXs[i] + halfWin));
            } else {
                continue;
            }
            int pixCnt = 0;
            int minXSlot = W, maxXSlot = -1;
            List<Integer> rowSpans = new ArrayList<>();
            for (int y = handY0; y < handY1; y++) {
                int rowCnt = 0;
                int rMinX = W, rMaxX = -1;
                for (int x = xl; x < xr; x++) {
                    if (chipMask[y * W + x] != 0) {
                        pixCnt++;
                        rowCnt++;
                        if (x < rMinX) rMinX = x;
                        if (x > rMaxX) rMaxX = x;
                    }
                }
                if (rowCnt >= 4) {
                    if (rMinX < minXSlot) minXSlot = rMinX;
                    if (rMaxX > maxXSlot) maxXSlot = rMaxX;
                    rowSpans.add(rMaxX - rMinX + 1);
                }
            }
            if (pixCnt < Math.max(25, (int) Math.pow(0.035 * W, 2)) || maxXSlot <= minXSlot) continue;
            double sx = 0.5 * (minXSlot + maxXSlot);
            double span98 = !rowSpans.isEmpty() ? percentileInt(rowSpans, 98.0) : (maxXSlot - minXSlot + 1.0);
            double wHand = Math.max(0.162 * W, Math.min(0.202 * W, span98));
            double hFlatHw = 0.257 * wHand;
            int fcL = Math.max(0, (int) Math.round(sx - hFlatHw * 0.74));
            int fcR = Math.min(W, (int) Math.round(sx + hFlatHw * 0.74));
            List<Integer> actR = new ArrayList<>();
            for (int y = handY0; y < handY1; y++) {
                int cnt = 0;
                for (int x = fcL; x < fcR; x++) if (chipMask[y * W + x] != 0) cnt++;
                if ((double) cnt / Math.max(1, fcR - fcL) >= 0.42) actR.add(y);
            }
            if (actR.size() < 6) continue;
            slotAct[i] = true;
            slotSx[i] = sx;
            slotW[i] = wHand;
            slotTopY[i] = actR.get(0);
            slotBotY[i] = actR.get(actR.size() - 1) + 0.5;
            actBots.add(slotBotY[i]);
            actWs.add(wHand);
        }

        double sharedFloorY = !actBots.isEmpty() ? medianDouble(actBots) : (0.824 * gameBotY);
        double sharedWHand = computeSharedHandWidth(slotAct, slotSx, actWs, W, traySpanPx, 0.160, 0.204, nomWHand);

        double[][] waitSamplePts = new double[3][2];
        for (int i = 0; i < 3; i++) {
            int sid = i + 1;
            List<Layer> wl = new ArrayList<>();
            waitSamplePts[i][0] = slotSx[i];
            waitSamplePts[i][1] = sharedFloorY - 0.40 * sharedWHand;

            if (slotAct[i]) {
                double floorY = Math.abs(slotBotY[i] - sharedFloorY) <= 6.0 ? sharedFloorY : slotBotY[i];
                // Exact physical geometry of hand hex stack:
                // - pure flat-top cap height (excluding all N side-skirt slices) = 0.7415 * trueWHand
                // - each chip (including top chip) adds 1 side-skirt slice = 0.0556 * trueWHand
                double pureCapH = 0.7415 * sharedWHand;
                double hSlice = 0.0556 * sharedWHand;
                double hRx = 0.50 * sharedWHand;
                double hFlatHw = 0.257 * sharedWHand;
                double hSlope = (0.405 * sharedWHand - 0.065 * sharedWHand) / Math.max(1.0, hRx - hFlatHw);

                int nChips = Math.max(1, Math.min(9, (int) Math.round((floorY - (slotTopY[i] + pureCapH)) / Math.max(1.2, hSlice))));
                int fcL = Math.max(0, (int) Math.round(slotSx[i] - hFlatHw * 0.74));
                int fcR = Math.min(W, (int) Math.round(slotSx[i] + hFlatHw * 0.74));
                int cy0H = Math.max(0, (int) Math.round(slotTopY[i] + 0.16 * pureCapH));
                int cy1H = Math.min(H, (int) Math.round(slotTopY[i] + 0.80 * pureCapH));
                List<Integer> cRs = new ArrayList<>(), cGs = new ArrayList<>(), cBs = new ArrayList<>();
                for (int y = cy0H; y < cy1H; y++) {
                    for (int x = fcL; x < fcR; x++) {
                        int idx = y * W + x;
                        if (chipMask[idx] != 0) {
                            int c = px[idx];
                            cRs.add((c >> 16) & 0xFF); cGs.add((c >> 8) & 0xFF); cBs.add(c & 0xFF);
                        }
                    }
                }
                int topCid = classifyChipPixels(cRs, cGs, cBs, capProto, sideProto, hueProto, true);
                if (topCid > 0) {
                    List<Integer> sliceCids = new ArrayList<>();
                    int dxLeft = Math.max(2, (int) Math.floor(hRx * 0.88));
                    int flatLim = Math.max(2, (int) Math.round(hFlatHw * 0.92));
                    double[] subOffs = {0.42, 0.56};
                    for (int k = 0; k < nChips - 1; k++) {
                        List<Integer> sRs = new ArrayList<>(), sGs = new ArrayList<>(), sBs = new ArrayList<>();
                        for (double subOff : subOffs) {
                            for (int dx = -dxLeft; dx <= flatLim; dx++) {
                                int pxi = (int) Math.round(slotSx[i] + dx);
                                if (pxi < 0 || pxi >= W) continue;
                                double yFl = floorY - Math.max(0.0, Math.abs(dx) - hFlatHw) * hSlope;
                                int pyi = (int) Math.round(yFl - (k + subOff) * hSlice);
                                if (pyi >= 0 && pyi < H && chipMask[pyi * W + pxi] != 0 && hsvV[pyi * W + pxi] >= 102) {
                                    int c = px[pyi * W + pxi];
                                    int r = (c >> 16) & 0xFF, g = (c >> 8) & 0xFF, b = c & 0xFF;
                                    sRs.add(r); sGs.add(g); sBs.add(b);
                                    // Give 2x weight to flat front center where y is strictly horizontal
                                    if (Math.abs(dx) <= flatLim) {
                                        sRs.add(r); sGs.add(g); sBs.add(b);
                                    }
                                }
                            }
                        }
                        int scid = topCid;
                        if (sRs.size() >= 3) {
                            int cand = classifyChipPixels(sRs, sGs, sBs, capProto, sideProto, hueProto, false);
                            if (cand > 0) scid = cand;
                        } else if (!sliceCids.isEmpty()) {
                            scid = sliceCids.get(sliceCids.size() - 1);
                        }
                        sliceCids.add(scid);
                    }
                    sliceCids.add(topCid);

                    for (int cvId : sliceCids) {
                        if (!wl.isEmpty() && wl.get(wl.size() - 1).colour == cvId) {
                            Layer last = wl.get(wl.size() - 1);
                            last.count = Math.min(9, last.count + 1);
                        } else {
                            wl.add(new Layer(cvId, 1));
                        }
                    }
                    waitSamplePts[i][1] = slotTopY[i] + 0.5 * pureCapH;
                }
            }
            out.waits.put(sid, wl);
        }

        Calibration cal = new Calibration();
        cal.centerX = cx0 / W;
        cal.centerY = cy0 / H;
        cal.scaleX = sc / W;
        cal.scaleY = sy / H;
        cal.trayY = (sharedFloorY - 0.40 * sharedWHand) / H;
        cal.traySpanX = traySpanPx / W;
        cal.sliceStepPx = sliceStep;
        out.calibration = cal;

        // Build webCompatibleJson (optional when org.json stub isn't present in pure JVM tests)
        try {
            JSONArray cellsJson = new JSONArray();
            JSONArray overlayCellsJson = new JSONArray();
            for (StageCellCfg scItem : effStageCfg.cells) {
                int cid = scItem.cellId;
                double[] cp = centered.get(cid);
                double bx = cx0 + (cp[0] / 100.0) * sc;
                double by = cy0 + (cp[1] / 100.0) * sy;
                double[] sp = samplePts.get(cid);
                CellState cs = out.cells[cid];

                JSONObject cj = new JSONObject();
                cj.put("cellId", cid);
                cj.put("exist", 1);
                cj.put("lockScore", cs.lockScore);
                cj.put("lockCardScore", cs.lockCardScore);
                JSONArray la = new JSONArray();
                for (Layer l : cs.layers) {
                    JSONObject lo = new JSONObject();
                    lo.put("colour", l.colour);
                    lo.put("count", l.count);
                    la.put(lo);
                }
                cj.put("layers", la);
                cellsJson.put(cj);

                JSONObject oj = new JSONObject();
                oj.put("cellId", cid);
                oj.put("nx", bx / W);
                oj.put("ny", by / H);
                oj.put("sampleNx", sp[0] / W);
                oj.put("sampleNy", sp[1] / H);
                oj.put("topColor", cs.topColor());
                oj.put("locked", cs.isLocked());
                overlayCellsJson.put(oj);
            }

            JSONArray waitsJson = new JSONArray();
            JSONArray overlayWaitsJson = new JSONArray();
            for (int i = 0; i < 3; i++) {
                int sid = i + 1;
                List<Layer> wl = out.waits.get(sid);
                JSONObject wj = new JSONObject();
                wj.put("slotId", sid);
                JSONArray la = new JSONArray();
                for (Layer l : wl) {
                    JSONObject lo = new JSONObject();
                    lo.put("colour", l.colour);
                    lo.put("count", l.count);
                    la.put(lo);
                }
                wj.put("layers", la);
                waitsJson.put(wj);

                JSONObject ow = new JSONObject();
                ow.put("slotId", sid);
                ow.put("nx", slotSx[i] / W);
                ow.put("ny", cal.trayY);
                ow.put("sampleNx", waitSamplePts[i][0] / W);
                ow.put("sampleNy", waitSamplePts[i][1] / H);
                ow.put("topColor", wl.isEmpty() ? 0 : wl.get(wl.size() - 1).colour);
                overlayWaitsJson.put(ow);
            }

            JSONObject root = new JSONObject();
            root.put("ok", true);
            root.put("imageWidth", W);
            root.put("imageHeight", H);
            root.put("detectedStageNo", out.detectedStageNo);
            root.put("detectedScore", out.detectedScore);
            root.put("detectedTargetScore", out.detectedTargetScore);
            JSONObject calJ = new JSONObject();
            calJ.put("centerX", Math.round(cal.centerX * 10000.0) / 10000.0);
            calJ.put("centerY", Math.round(cal.centerY * 10000.0) / 10000.0);
            calJ.put("scaleX", Math.round(cal.scaleX * 10000.0) / 10000.0);
            calJ.put("scaleY", Math.round(cal.scaleY * 10000.0) / 10000.0);
            calJ.put("trayY", Math.round(cal.trayY * 10000.0) / 10000.0);
            calJ.put("traySpanX", Math.round(cal.traySpanX * 10000.0) / 10000.0);
            root.put("calibration", calJ);
            root.put("cells", cellsJson);
            root.put("waits", waitsJson);
            JSONObject ov = new JSONObject();
            ov.put("cells", overlayCellsJson);
            ov.put("waits", overlayWaitsJson);
            root.put("overlay", ov);
            out.webCompatibleJson = root;
        } catch (Throwable ignored) {}

        return out;
    }

    public static class CellDiff {
        public int cellId;
        public List<Layer> appLayers = new ArrayList<>();
        public List<Layer> gameLayers = new ArrayList<>();
        public boolean selected = true;
        public boolean occludedHint = false;
        public int occludedByCellId = -1;
        public String note = "";
    }

    public static class CausalHistoryStep {
        public int seq;
        public String tag = "";
        public int scoreBefore;
        public int scoreAfter;
        public int screenScore = -1;
        public CellState[] boardBefore;
        public CellState[] boardAfter;
        public Map<Integer, List<Layer>> handBefore;
        public SolvePlan planBefore;
        public int measuredRotDeg = 0;
        public float[] measuredHeightSlices;
        public int[][] cellThumbRgb;
        public int[] measuredTopColor;
        public boolean[] measuredOccupied;

        public static CausalHistoryStep fromVision(
                int seq, String tag, int scoreBefore, int scoreAfter, int screenScore,
                CellState[] boardBefore, CellState[] boardAfter,
                Map<Integer, List<Layer>> handBefore, SolvePlan planBefore,
                VisionOutput vo, int rotDeg
        ) {
            CausalHistoryStep h = new CausalHistoryStep();
            h.seq = seq;
            h.tag = tag != null ? tag : "";
            h.scoreBefore = scoreBefore;
            h.scoreAfter = scoreAfter;
            h.screenScore = screenScore;
            h.boardBefore = boardBefore != null ? cloneBoard(boardBefore) : null;
            h.boardAfter = boardAfter != null ? cloneBoard(boardAfter) : null;
            if (handBefore != null) {
                h.handBefore = new HashMap<>();
                for (int sid = 1; sid <= 3; sid++) {
                    List<Layer> cp = new ArrayList<>();
                    List<Layer> src = handBefore.get(sid);
                    if (src != null) for (Layer l : src) cp.add(l.copy());
                    h.handBefore.put(sid, cp);
                }
            }
            h.planBefore = planBefore;
            h.measuredRotDeg = rotDeg;
            if (vo != null) {
                h.measuredHeightSlices = vo.measuredHeightSlices != null ? vo.measuredHeightSlices.clone() : null;
                if (vo.cellThumbRgb != null) {
                    h.cellThumbRgb = new int[MAX_POS_COUNT + 1][];
                    for (int i = 0; i <= MAX_POS_COUNT; i++) {
                        if (vo.cellThumbRgb[i] != null) h.cellThumbRgb[i] = vo.cellThumbRgb[i].clone();
                    }
                }
                if (vo.cells != null) {
                    h.measuredTopColor = new int[MAX_POS_COUNT + 1];
                    h.measuredOccupied = new boolean[MAX_POS_COUNT + 1];
                    for (int i = 1; i <= MAX_POS_COUNT && i < vo.cells.length; i++) {
                        CellState cs = vo.cells[i];
                        if (cs != null && cs.exist == 1 && !cs.isLocked() && cs.hasBlocks()) {
                            h.measuredOccupied[i] = true;
                            h.measuredTopColor[i] = cs.topColor();
                        }
                    }
                }
            }
            return h;
        }
    }

    public static class BoardDiffResult {
        public int stageNo;
        public int appScore;
        public int detectedScore = -1;
        public int rotationDeg = 0;
        public int recommendedTurnDeltaDeg = 0;
        public int recommendedTargetRotDeg = 0;
        public boolean multiAngleAllClear = false;
        public boolean invalidFrame = false;
        public boolean sameAngleNotRotated = false;
        public boolean hasPopupObstruction = false;
        public boolean causalExactMatch = false;
        public String turnIconOnly = "";
        public String detectedTurnBadge = "";
        public String causalDiagnosisRu = "";
        public String solutionActionLabelRu = "";
        public String occlusionGuidanceRu = "";
        public String lastScreenId = "";
        public String uiScreenId = "";
        public java.util.Set<Integer> scannedAngles = new java.util.LinkedHashSet<>();
        public java.util.Set<Integer> cleanCells = new HashSet<>();
        public Map<Integer, Integer> occludedCellBlockers = new java.util.LinkedHashMap<>();
        public Map<Integer, List<Layer>> fullScannedBoard = new HashMap<>();
        public Map<Integer, List<Layer>> scannedWaits = new HashMap<>();
        public VisionOutput latestVisionOutput = null;
        public List<CellDiff> diffs = new ArrayList<>();
    }

    public static boolean layersEqual(List<Layer> a, List<Layer> b) {
        int na = (a == null) ? 0 : a.size();
        int nb = (b == null) ? 0 : b.size();
        if (na != nb) return false;
        for (int i = 0; i < na; i++) {
            Layer la = a.get(i), lb = b.get(i);
            if (la.colour != lb.colour || la.count != lb.count) return false;
        }
        return true;
    }

    private static int sumLayerBlocks(List<Layer> list) {
        if (list == null) return 0;
        int s = 0;
        for (Layer l : list) s += l.count;
        return s;
    }

    private static double[] latticeWorldPos(int col, int row, int rotationDeg) {
        double x = -213.0 + (col - 1) * 86.0;
        double topZ = (col % 2 == 1) ? 258.0 : 308.0;
        double z = topZ - (row - 1) * 100.0;
        double dx = x - 2.0;
        double dz = z - (-17.0);
        double rad = Math.toRadians(((rotationDeg % 360) + 360) % 360);
        double rx = dx * Math.cos(rad) + dz * Math.sin(rad);
        double rz = -dx * Math.sin(rad) + dz * Math.cos(rad);
        return new double[]{rx, -rz};
    }

    private static int[][] hexNeighborsColRow(int col, int row) {
        if (col % 2 == 1) {
            return new int[][]{{col, row - 1}, {col, row + 1}, {col - 1, row - 1}, {col - 1, row}, {col + 1, row - 1}, {col + 1, row}};
        } else {
            return new int[][]{{col, row - 1}, {col, row + 1}, {col - 1, row}, {col - 1, row + 1}, {col + 1, row}, {col + 1, row + 1}};
        }
    }

    /**
     * Fast (~3 ms) 6-angle board rotation detector (0, 60, 120, 180, 240, 300 deg).
     * Combines stage hex silhouette + outer-ring absence + aspect ratio + locked hexes + known stacks.
     */
    public static int detectBoardRotationDeg(
            int[] px,
            int W,
            int H,
            StageCfg stg,
            int effScore,
            CellState[] knownBoard,
            int prevRotationDeg
    ) {
        int normPrev = (((prevRotationDeg % 360) + 360) % 360 / 60) * 60;
        if (px == null || stg == null || stg.cells == null || stg.cells.isEmpty() || W < 120 || H < 160) {
            return normPrev;
        }
        if (W > 360) {
            int nW = 360;
            int nH = Math.max(180, Math.round((float) H * nW / W));
            int[] dpx = new int[nW * nH];
            for (int y = 0; y < nH; y++) {
                int y0i = Math.min(H - 1, (y * H) / nH);
                for (int x = 0; x < nW; x++) {
                    int x0i = Math.min(W - 1, (x * W) / nW);
                    dpx[y * nW + x] = px[y0i * W + x0i];
                }
            }
            px = dpx;
            W = nW;
            H = nH;
        }

        int x0 = (int) (0.03 * W), x1 = (int) (0.97 * W);
        List<Integer> bgRs = new ArrayList<>(), bgGs = new ArrayList<>(), bgBs = new ArrayList<>();
        int byA = Math.max(0, (int) (0.18 * H)), byB = Math.min(H, (int) (0.24 * H));
        for (int y = byA; y < byB; y += 2) {
            for (int x = x0; x < Math.min(W, (int) (0.14 * W)); x += 2) {
                int c = px[y * W + x];
                bgRs.add((c >> 16) & 0xFF); bgGs.add((c >> 8) & 0xFF); bgBs.add(c & 0xFF);
            }
            for (int x = Math.max(0, (int) (0.86 * W)); x < x1; x += 2) {
                int c = px[y * W + x];
                bgRs.add((c >> 16) & 0xFF); bgGs.add((c >> 8) & 0xFF); bgBs.add(c & 0xFF);
            }
        }
        double bgR = bgRs.isEmpty() ? 190.0 : medianInt(bgRs);
        double bgG = bgGs.isEmpty() ? 140.0 : medianInt(bgGs);
        double bgB = bgBs.isEmpty() ? 80.0 : medianInt(bgBs);
        double bgSum = Math.max(1.0, bgR + bgG + bgB);
        double bgRn = bgR / bgSum, bgGn = bgG / bgSum;

        int nPix = W * H;
        byte[] emptyM = new byte[nPix];
        byte[] lockM = new byte[nPix];
        byte[] chipM = new byte[nPix];
        byte[] tileFg = new byte[nPix];

        for (int i = 0; i < nPix; i++) {
            int c = px[i];
            int r = (c >> 16) & 0xFF, g = (c >> 8) & 0xFF, b = c & 0xFF;
            int mx = Math.max(r, Math.max(g, b));
            int mn = Math.min(r, Math.min(g, b));
            double sat = mx > 1 ? (double) (mx - mn) / mx : 0.0;
            double s = Math.max(1.0, r + g + b);
            double chrD = Math.abs(r / s - bgRn) + Math.abs(g / s - bgGn);

            boolean isEmpty = (r > 145 && r < 222 && g > 125 && g < 198 && b > 92 && b < 162
                    && (r - b) > 25 && (r - b) < 84 && Math.abs((r - g) - (g - b)) < 26);
            boolean isLock = (mx >= 34 && mx <= 122 && (r - b) >= 14 && (r - g) >= 6
                    && g >= b && sat >= 0.20 && sat <= 0.65);
            boolean isChip = (sat > 0.42 && chrD > 0.085 && mx > 70);
            boolean neonAr = ((r < 68 && g > 175 && b > 80) || (r > 215 && g > 145 && b < 40 && sat > 0.78));

            if (isEmpty) emptyM[i] = 1;
            if (isLock) lockM[i] = 1;
            if (isChip && !neonAr) chipM[i] = 1;
            if (isEmpty || isLock || (isChip && !neonAr)) tileFg[i] = 1;
        }

        int yScanA = Math.max(0, (int) (0.19 * H));
        int yScanB = Math.min(H, (int) (0.680 * H));
        int minColHits = Math.max(8, (int) (0.048 * H));
        int minRowHits = Math.max(8, (int) (0.065 * W));
        int bx0 = -1, bx1 = -1, by0 = -1, by1 = -1;
        for (int x = x0; x < x1; x++) {
            int cnt = 0;
            for (int y = yScanA; y < yScanB; y++) if (tileFg[y * W + x] != 0) cnt++;
            if (cnt >= minColHits) {
                if (bx0 < 0) bx0 = x;
                bx1 = x;
            }
        }
        for (int y = yScanA; y < yScanB; y++) {
            int cnt = 0;
            for (int x = x0; x < x1; x++) if (tileFg[y * W + x] != 0) cnt++;
            if (cnt >= minRowHits) {
                if (by0 < 0) by0 = y;
                by1 = y;
            }
        }
        if (bx0 < 0 || bx1 - bx0 < 40 || by0 < 0 || by1 - by0 < 40) {
            return normPrev;
        }

        double obsAspect = (double) (by1 - by0) / Math.max(1.0, (double) (bx1 - bx0));

        Set<Long> existRc = new HashSet<>();
        Set<Integer> lockSet = new HashSet<>();
        for (StageCellCfg sc : stg.cells) {
            int col = (sc.cellId - 1) / MAX_ROW + 1;
            int row = (sc.cellId - 1) % MAX_ROW + 1;
            existRc.add((((long) col) << 16) | (row & 0xFFFFL));
            if (sc.unlockCellScore > effScore) {
                lockSet.add(sc.cellId);
            }
        }
        List<int[]> outerRingRc = new ArrayList<>();
        Set<Long> seenRing = new HashSet<>();
        for (StageCellCfg sc : stg.cells) {
            int col = (sc.cellId - 1) / MAX_ROW + 1;
            int row = (sc.cellId - 1) % MAX_ROW + 1;
            for (int[] nb : hexNeighborsColRow(col, row)) {
                long key = (((long) nb[0]) << 16) | (nb[1] & 0xFFFFL);
                if (!existRc.contains(key) && seenRing.add(key)) {
                    outerRingRc.add(nb);
                }
            }
        }

        int knownStackCount = 0;
        if (knownBoard != null) {
            for (StageCellCfg sc : stg.cells) {
                int cid = sc.cellId;
                if (cid >= 1 && cid < knownBoard.length && knownBoard[cid] != null
                        && !knownBoard[cid].isLocked() && knownBoard[cid].hasBlocks()) {
                    knownStackCount++;
                }
            }
        }

        int bestRot = normPrev;
        double bestScore = -1e9;
        int patchR = Math.max(4, W / 72);
        int lockR = Math.max(6, W / 44);

        for (int rot = 0; rot < 360; rot += 60) {
            double minUx = 1e9, maxUx = -1e9, minUy = 1e9, maxUy = -1e9;
            Map<Integer, double[]> pts = new HashMap<>();
            for (StageCellCfg sc : stg.cells) {
                double[] wp = cellWorldPos(sc.cellId, rot);
                pts.put(sc.cellId, wp);
                if (wp[0] < minUx) minUx = wp[0];
                if (wp[0] > maxUx) maxUx = wp[0];
                if (wp[1] < minUy) minUy = wp[1];
                if (wp[1] > maxUy) maxUy = wp[1];
            }
            double syFactor = 0.815;
            double expWUnits = (maxUx - minUx) + 114.0;
            double expHUnits = syFactor * ((maxUy - minUy) + 118.0);
            double expAspect = expHUnits / Math.max(1.0, expWUnits);
            double aspectErr = Math.abs(obsAspect - expAspect);

            double scX = (bx1 - bx0) / expWUnits;
            double scY = (by1 - by0) / expHUnits;
            double sc = 0.5 * (scX + scY);
            double scaleErr = Math.abs(scX - scY) / Math.max(1e-4, sc);
            double cx0 = 0.5 * (bx0 + bx1) - sc * 0.5 * (minUx + maxUx);
            double cy0 = (by1 - 52.0 * sc * syFactor) - sc * syFactor * maxUy;

            double sIn = 0.0;
            double sLock = 0.0;
            double sKnown = 0.0;

            for (StageCellCfg scItem : stg.cells) {
                int cid = scItem.cellId;
                double[] wp = pts.get(cid);
                int pxC = (int) Math.round(cx0 + sc * wp[0]);
                int pyC = (int) Math.round(cy0 + sc * syFactor * wp[1]);

                int yA = Math.max(0, pyC - patchR), yB = Math.min(H, pyC + patchR + 1);
                int xA = Math.max(0, pxC - patchR), xB = Math.min(W, pxC + patchR + 1);
                int hits = 0, tot = 0;
                for (int y = yA; y < yB; y++) {
                    for (int x = xA; x < xB; x++) {
                        tot++;
                        if (tileFg[y * W + x] != 0) hits++;
                    }
                }
                if (tot > 0) sIn += (double) hits / tot;

                if (lockSet.contains(cid)) {
                    int lyA = Math.max(0, pyC - lockR), lyB = Math.min(H, pyC + lockR + 1);
                    int lxA = Math.max(0, pxC - lockR), lxB = Math.min(W, pxC + lockR + 1);
                    int lHits = 0, lTot = 0;
                    for (int y = lyA; y < lyB; y++) {
                        for (int x = lxA; x < lxB; x++) {
                            lTot++;
                            if (lockM[y * W + x] != 0) lHits++;
                        }
                    }
                    if (lTot > 0) sLock += (double) lHits / lTot;
                }

                if (knownStackCount > 0 && cid < knownBoard.length && knownBoard[cid] != null && !knownBoard[cid].isLocked()) {
                    CellState kcs = knownBoard[cid];
                    if (kcs.hasBlocks()) {
                        int topCol = kcs.topColor();
                        int nBlocks = sumLayerBlocks(kcs.layers);
                        int capY = (int) Math.round(pyC - nBlocks * 10.3 * sc);
                        int cyA = Math.max(0, capY - patchR), cyB = Math.min(H, capY + patchR + 1);
                        int cxA = Math.max(0, pxC - patchR), cxB = Math.min(W, pxC + patchR + 1);
                        int cHits = 0, cTot = 0;
                        long sumR = 0, sumG = 0, sumB = 0;
                        for (int y = cyA; y < cyB; y++) {
                            for (int x = cxA; x < cxB; x++) {
                                cTot++;
                                if (chipM[y * W + x] != 0) {
                                    cHits++;
                                    int rgb = px[y * W + x];
                                    sumR += (rgb >> 16) & 0xFF;
                                    sumG += (rgb >> 8) & 0xFF;
                                    sumB += rgb & 0xFF;
                                }
                            }
                        }
                        if (cTot > 0 && cHits >= cTot / 4 && topCol >= 1 && topCol <= 8) {
                            double mR = (double) sumR / cHits, mG = (double) sumG / cHits, mB = (double) sumB / cHits;
                            int[] ref = DEFAULT_PALETTE_RGB[topCol];
                            double dist = Math.hypot(mR - ref[0], Math.hypot(mG - ref[1], mB - ref[2]));
                            if (dist < 85.0) {
                                sKnown += 1.0 - (dist / 120.0);
                            } else {
                                sKnown -= 0.25;
                            }
                        } else {
                            sKnown -= 0.20;
                        }
                    }
                }
            }

            double sOut = 0.0;
            int outR = Math.max(3, W / 82);
            for (int[] rc : outerRingRc) {
                double[] wp = latticeWorldPos(rc[0], rc[1], rot);
                int pxC = (int) Math.round(cx0 + sc * wp[0]);
                int pyC = (int) Math.round(cy0 + sc * syFactor * wp[1]);
                if (pxC >= outR + 1 && pxC < W - outR - 1 && pyC >= outR + 1 && pyC < H - outR - 1) {
                    int oHits = 0, oTot = 0;
                    for (int y = pyC - outR; y <= pyC + outR; y++) {
                        for (int x = pxC - outR; x <= pxC + outR; x++) {
                            oTot++;
                            if (emptyM[y * W + x] != 0 || lockM[y * W + x] != 0) oHits++;
                        }
                    }
                    if (oTot > 0) sOut += (double) oHits / oTot;
                }
            }

            double total = (sIn / Math.max(1, stg.cells.size()))
                    - 1.20 * (sOut / Math.max(1, outerRingRc.size()))
                    + (lockSet.isEmpty() ? 0.0 : 1.80 * (sLock / lockSet.size()))
                    - 0.85 * aspectErr
                    + (knownStackCount > 0 ? 0.45 * (sKnown / knownStackCount) : 0.0)
                    + (rot == normPrev ? 0.012 : 0.0);

            if (total > bestScore) {
                bestScore = total;
                bestRot = rot;
            }
        }
        return bestRot;
    }

    /**
     * Geometric 3D occlusion detector at angle rotDeg: returns frontBlockerCellId (>0) if cid's
     * lower layers (or entire short stack) can be hidden behind a front tower at rotDeg; else -1.
     */
    public static int findFrontBlockerAtAngle(
            StageCfg stgCfg,
            int rotDeg,
            int cid,
            Map<Integer, List<Layer>> boardLayersMap
    ) {
        if (stgCfg == null || stgCfg.cells == null || boardLayersMap == null) return -1;
        List<Layer> selfL = boardLayersMap.get(cid);
        int selfBlocks = sumLayerBlocks(selfL);

        double[] wpCur = cellWorldPos(cid, rotDeg);
        int directBlocker = -1, directBlocks = 0;
        int farBlocker = -1, farBlocks = 0;
        int diagBlocker = -1, diagBlocks = 0;

        for (StageCellCfg other : stgCfg.cells) {
            int ocid = other.cellId;
            if (ocid == cid) continue;
            int ob = sumLayerBlocks(boardLayersMap.get(ocid));
            if (ob <= 0) continue;
            double[] wpO = cellWorldPos(ocid, rotDeg);
            double dxW = Math.abs(wpO[0] - wpCur[0]);
            double dyW = wpO[1] - wpCur[1];
            if (dxW < 18.0 && dyW > 40.0 && dyW < 135.0 && ob > directBlocks) {
                directBlocks = ob;
                directBlocker = ocid;
            } else if (dxW < 18.0 && dyW >= 135.0 && dyW < 225.0 && ob > farBlocks) {
                farBlocks = ob;
                farBlocker = ocid;
            } else if (dxW >= 18.0 && dxW < 105.0 && dyW > 20.0 && dyW < 90.0 && ob > diagBlocks) {
                diagBlocks = ob;
                diagBlocker = ocid;
            }
        }

        if (selfBlocks > 0) {
            if (directBlocks >= 2) return directBlocker;
            if (farBlocks >= 8) return farBlocker;
            if (diagBlocks >= 6) return diagBlocker;
        } else {
            // Even an apparently empty hex can hide a 1-2 block stack if a >=6-block tower stands directly in front!
            if (directBlocks >= 6) return directBlocker;
        }
        return -1;
    }

    public static String formatTurnIconOnly(int deltaDeg) {
        int norm = (((deltaDeg % 360) + 360) % 360);
        switch (norm) {
            case 60:
                return "⬅ ×1";
            case 120:
                return "⬅ ×2";
            case 180:
                return "↻ ×3";
            case 240:
                return "➡ ×2";
            case 300:
                return "➡ ×1";
            default:
                return "";
        }
    }

    public static String formatTurnDeltaRu(int deltaDeg) {
        String ic = formatTurnIconOnly(deltaDeg);
        return ic.isEmpty() ? "🔄" : ic;
    }

    private static void populateOcclusionAdvice(BoardDiffResult out, StageCfg stgCfg, int baseAppRotDeg) {
        if (out == null || stgCfg == null) return;
        if (out.invalidFrame) return;

        int normBase = (((baseAppRotDeg % 360) + 360) % 360);
        int deltaFromBase = (((out.rotationDeg - normBase) % 360) + 360) % 360;
        out.detectedTurnBadge = (deltaFromBase == 0)
                ? ""
                : (formatTurnIconOnly(deltaFromBase) + " (" + out.rotationDeg + "°)");

        if (out.occludedCellBlockers.isEmpty()) {
            out.recommendedTurnDeltaDeg = 0;
            out.recommendedTargetRotDeg = out.rotationDeg;
            out.turnIconOnly = "";
            out.multiAngleAllClear = (out.scannedAngles.size() >= 2);
        } else {
            out.multiAngleAllClear = false;
            int[] candidateDeltas = {60, -60, 120, -120, 180};
            int bestDelta = 0;
            int bestCleared = 0;
            for (int delta : candidateDeltas) {
                int candRot = (((out.rotationDeg + delta) % 360) + 360) % 360;
                if (out.scannedAngles.contains(candRot)) continue;
                int cleared = 0;
                for (int occCid : out.occludedCellBlockers.keySet()) {
                    if (findFrontBlockerAtAngle(stgCfg, candRot, occCid, out.fullScannedBoard) < 0) {
                        cleared++;
                    }
                }
                if (cleared > bestCleared) {
                    bestCleared = cleared;
                    bestDelta = delta;
                }
            }
            out.recommendedTurnDeltaDeg = bestDelta;
            out.recommendedTargetRotDeg = (((out.rotationDeg + bestDelta) % 360) + 360) % 360;
            out.turnIconOnly = (bestCleared > 0) ? formatTurnIconOnly(bestDelta) : "";
        }

        // Keep guidance ultra-clean: ZERO verbose cell-list text walls!
        if (out.hasPopupObstruction) {
            out.occlusionGuidanceRu = "⚠ На поле открыта плашка «Отбор» — закройте её для полного скана";
        } else if (out.sameAngleNotRotated && !out.turnIconOnly.isEmpty()) {
            out.occlusionGuidanceRu = "⚠ Поверните поле: " + out.turnIconOnly;
        } else {
            out.occlusionGuidanceRu = "";
        }
    }

    private static boolean isBlankOrNonGameFrame(int[] px, int W, int H) {
        if (px == null || W < 80 || H < 120) return true;
        int x0 = (int) (0.15 * W), x1 = (int) (0.85 * W);
        int y0 = (int) (0.18 * H), y1 = (int) (0.76 * H);
        long sumV = 0;
        int tot = 0, colorfulOrTile = 0;
        for (int y = y0; y < y1; y += 3) {
            for (int x = x0; x < x1; x += 3) {
                int c = px[y * W + x];
                int r = (c >> 16) & 0xFF, g = (c >> 8) & 0xFF, b = c & 0xFF;
                int mx = Math.max(r, Math.max(g, b));
                int mn = Math.min(r, Math.min(g, b));
                sumV += mx;
                tot++;
                if (mx >= 65 && (mx - mn) >= 24) colorfulOrTile++;
            }
        }
        if (tot == 0) return true;
        double meanV = (double) sumV / tot;
        double actFrac = (double) colorfulOrTile / tot;
        return meanV < 28.0 || actFrac < 0.03;
    }

    /**
     * Pre-analysis 6-angle board orientation & turn-direction detector.
     * Runs BEFORE cell-level diff analysis on BOTH «? Сверить» and «🔄 Досканировать ракурс»!
     * Uses fast coarse mask/color matching across all 6 angles {0, 60, 120, 180, 240, 300}
     * followed by full analyzeScreenshotArgb verification on the top candidate orientations.
     */
    private static VisionOutput detectBoardAnglePreScan(
            int[] px, int W, int H, StageCfg stgCfg, int effScore, int baseRotationDeg,
            BoardDiffResult prevMultiScan, CellState[] currentBoard,
            Calibration userCalib, Map<Integer, int[]> customPalette, Map<Integer, StageCfg> allStages,
            int[] detectedRotOut
    ) {
        int normBase = (((baseRotationDeg % 360) + 360) % 360);
        // Check if we have any known reference stacks or active locks to disambiguate rotation
        int refLocks = 0, refStacks = 0;
        for (StageCellCfg item : stgCfg.cells) {
            int cid = item.cellId;
            if (item.unlockCellScore > effScore || item.unlockCardScore > effScore) refLocks++;
            List<Layer> rl = (prevMultiScan != null && prevMultiScan.fullScannedBoard != null)
                    ? prevMultiScan.fullScannedBoard.get(cid) : null;
            if ((rl == null || rl.isEmpty()) && currentBoard != null && cid < currentBoard.length
                    && currentBoard[cid] != null && !currentBoard[cid].isLocked()) {
                rl = currentBoard[cid].layers;
            }
            if (rl != null && !rl.isEmpty()) refStacks++;
        }
        if (refLocks == 0 && refStacks == 0) {
            if (detectedRotOut != null && detectedRotOut.length > 0) detectedRotOut[0] = normBase;
            return analyzeScreenshotArgb(px, W, H, stgCfg, effScore, normBase, userCalib, customPalette, allStages);
        }

        // Fast-path: on initial '? Сверить' (prevMultiScan == null), check normBase first!
        // When the player did NOT rotate the board, normBase immediately matches >=75% of stacks with 0 lock contradictions,
        // avoiding 6x coarse camera fits + 3 extra full board scans (~4x speedup)!
        VisionOutput cachedNormVo = null;
        if (prevMultiScan == null && refStacks >= 3) {
            cachedNormVo = analyzeScreenshotArgb(px, W, H, stgCfg, effScore, normBase, userCalib, customPalette, allStages);
            int matchedTop = 0, visRefStacks = 0, lockContra = 0;
            for (StageCellCfg item : stgCfg.cells) {
                int cid = item.cellId;
                if (cachedNormVo.popupCovered != null && cachedNormVo.popupCovered[cid]) continue;
                CellState gcs = cachedNormVo.cells[cid];
                if (item.unlockCellScore > effScore) {
                    if (gcs != null && !gcs.isLocked()) lockContra++;
                    continue;
                }
                List<Layer> refL = (currentBoard != null && cid < currentBoard.length && currentBoard[cid] != null
                        && !currentBoard[cid].isLocked()) ? currentBoard[cid].layers : null;
                if (refL != null && !refL.isEmpty()) {
                    visRefStacks++;
                    if (gcs != null && !gcs.isLocked() && !gcs.layers.isEmpty()
                            && gcs.topColor() == refL.get(refL.size() - 1).colour) {
                        matchedTop++;
                    }
                }
            }
            if (lockContra == 0 && visRefStacks >= 3 && matchedTop >= Math.max(2, (int) Math.ceil(0.75 * visRefStacks))) {
                if (detectedRotOut != null && detectedRotOut.length > 0) detectedRotOut[0] = normBase;
                return cachedNormVo;
            }
        }

        // Fast coarse pre-ranking across all 6 angles (~4 ms total)
        int nPix = W * H;
        int[] hsvH = new int[nPix], hsvS = new int[nPix], hsvV = new int[nPix];
        for (int i = 0; i < nPix; i += 1) {
            int c = px[i];
            int[] hsv = rgbToHsvOpenCv((c >> 16) & 0xFF, (c >> 8) & 0xFF, c & 0xFF);
            hsvH[i] = hsv[0]; hsvS[i] = hsv[1]; hsvV[i] = hsv[2];
        }
        boolean[] td = detectThemeAndRedVignette(px, hsvH, hsvV, W, H, 0, H);
        boolean warm = td[0], redDanger = td[1];
        byte[] chipM = new byte[nPix], empM = new byte[nPix], lockM = new byte[nPix];
        for (int y = 0; y < H; y++) {
            int ro = y * W;
            for (int x = 0; x < W; x++) {
                int i = ro + x;
                int hc = hsvH[i], sc = hsvS[i], vc = hsvV[i];
                int rc = (px[i] >> 16) & 0xFF, gc = (px[i] >> 8) & 0xFF, bc = px[i] & 0xFF;
                if (redDanger && isRedVignetteBorderPixel(x, y, W, 0, H, rc, gc, bc, hc)) continue;
                if (warm) {
                    if (((hc <= 8 || hc >= 32) && sc >= 98 && vc >= 86) ||
                        (hc > 8 && hc < 32 && sc >= 174 && vc >= 115 && (bc <= 74 || (rc - bc) >= 152))) chipM[i] = 1;
                    else if (hc >= 15 && hc <= 26 && sc >= 70 && sc <= 116 && vc >= 155 && vc <= 208) empM[i] = 1;
                    else if (hc >= 11 && hc <= 25 && sc >= 78 && sc <= 168 && vc >= 74 && vc <= 138) lockM[i] = 1;
                } else {
                    if (sc >= 96 && vc >= 115) chipM[i] = 1;
                    else if (sc < 85 && vc >= 68 && vc <= 130) empM[i] = 1;
                    else if (sc < 85 && vc >= 42 && vc < 68) lockM[i] = 1;
                }
            }
        }

        int[] angles = {0, 60, 120, 180, 240, 300};
        double[] coarseScores = new double[6];
        for (int ai = 0; ai < 6; ai++) {
            int rot = angles[ai];
            double minX = 1e9, maxX = -1e9, minY = 1e9, maxY = -1e9;
            Map<Integer, double[]> rawMap = new HashMap<>();
            for (StageCellCfg scItem : stgCfg.cells) {
                double[] wp = cellWorldPos(scItem.cellId, rot);
                rawMap.put(scItem.cellId, wp);
                if (wp[0] < minX) minX = wp[0];
                if (wp[0] > maxX) maxX = wp[0];
                if (wp[1] < minY) minY = wp[1];
                if (wp[1] > maxY) maxY = wp[1];
            }
            double midX = 0.5 * (minX + maxX), midY = 0.5 * (minY + maxY);
            List<double[]> actPts = new ArrayList<>();
            for (StageCellCfg scItem : stgCfg.cells) {
                double[] wp = rawMap.get(scItem.cellId);
                actPts.add(new double[]{wp[0] - midX, wp[1] - midY});
            }
            double[] cam = (userCalib != null)
                    ? new double[]{userCalib.centerX * W, userCalib.centerY * H, userCalib.scaleX * W, userCalib.scaleY * H}
                    : fitBoardCamera(px, actPts, minX - midX, maxX - midX, maxY - midY, chipM, empM, lockM, W, H);
            double cx0 = cam[0], cy0 = cam[1], sc = cam[2], sy = cam[3];

            double cScore = (rot == normBase && prevMultiScan == null) ? 0.35 : 0.0;
            for (StageCellCfg scItem : stgCfg.cells) {
                int cid = scItem.cellId;
                double[] wp = rawMap.get(cid);
                double bx = cx0 + ((wp[0] - midX) / 100.0) * sc;
                double by = cy0 + ((wp[1] - midY) / 100.0) * sy;
                int x0 = Math.max(0, (int) Math.round(bx - 0.22 * sc)), x1 = Math.min(W, (int) Math.round(bx + 0.22 * sc));
                int y0 = Math.max(0, (int) Math.round(by - 0.42 * sc)), y1 = Math.min(H, (int) Math.round(by + 0.02 * sc));
                int tot = 0, chp = 0, emp = 0, lck = 0;
                for (int y = y0; y < y1; y += 2) {
                    for (int x = x0; x < x1; x += 2) {
                        int idx = y * W + x;
                        tot++;
                        if (chipM[idx] != 0) chp++;
                        else if (empM[idx] != 0) emp++;
                        else if (lockM[idx] != 0) lck++;
                    }
                }
                if (tot < 4) continue;
                boolean obsLock = (double) lck / tot >= 0.20 && (double) chp / tot < 0.22;
                boolean obsOcc = (double) chp / tot >= 0.22;
                if (scItem.unlockCellScore > effScore) {
                    cScore += obsLock ? 3.2 : (obsOcc ? -2.0 : 0.3);
                } else if (scItem.unlockCardScore > effScore) {
                    cScore += obsOcc ? 3.0 : -1.8;
                } else {
                    List<Layer> refL = (prevMultiScan != null && prevMultiScan.fullScannedBoard != null
                            && prevMultiScan.fullScannedBoard.containsKey(cid))
                            ? prevMultiScan.fullScannedBoard.get(cid)
                            : ((currentBoard != null && cid < currentBoard.length && currentBoard[cid] != null)
                                    ? currentBoard[cid].layers : null);
                    boolean refOcc = refL != null && !refL.isEmpty();
                    if (refOcc && obsOcc) cScore += 2.2;
                    else if (!refOcc && !obsOcc && !obsLock) cScore += 1.4;
                    else cScore -= 1.2;
                }
            }
            coarseScores[ai] = cScore;
        }

        // Pick top-3 coarse angles (always including normBase) for full exact stack-top verification
        Integer[] order = {0, 1, 2, 3, 4, 5};
        Arrays.sort(order, (a, b) -> Double.compare(coarseScores[b], coarseScores[a]));
        Set<Integer> testRots = new java.util.LinkedHashSet<>();
        testRots.add(angles[order[0]]);
        testRots.add(angles[order[1]]);
        testRots.add(angles[order[2]]);
        testRots.add(normBase);

        int bestRot = normBase;
        double bestExactScore = -1e18;
        VisionOutput bestVo = null;
        for (int rot : testRots) {
            VisionOutput cand = (rot == normBase && cachedNormVo != null)
                    ? cachedNormVo
                    : analyzeScreenshotArgb(px, W, H, stgCfg, effScore, rot, userCalib, customPalette, allStages);
            double sc = (rot == normBase && prevMultiScan == null) ? 0.40 : 0.0;
            for (StageCellCfg item : stgCfg.cells) {
                int cid = item.cellId;
                if (cand.popupCovered != null && cand.popupCovered[cid]) continue;
                CellState gcs = cand.cells[cid];
                if (item.unlockCellScore > effScore) {
                    if (gcs != null && gcs.isLocked()) sc += 2.8;
                    else sc -= 1.2;
                    continue;
                }
                if (item.unlockCardScore > effScore) {
                    // Verify that at this candidate rotation `rot`, `cid` actually holds saturated rainbow tower pixels!
                    if (cand.cellThumbRgb != null && cid < cand.cellThumbRgb.length && cand.cellThumbRgb[cid] != null) {
                        int chHits = 0;
                        int[] th = cand.cellThumbRgb[cid];
                        for (int rgb : th) {
                            int r = (rgb >> 16) & 0xFF, g = (rgb >> 8) & 0xFF, b = rgb & 0xFF;
                            int mx = Math.max(r, Math.max(g, b)), mn = Math.min(r, Math.min(g, b));
                            if (mx >= 95 && (mx - mn) >= 58) chHits++;
                        }
                        sc += ((double) chHits / th.length >= 0.36) ? 3.2 : -2.2;
                    }
                    continue;
                }
                List<Layer> refL = (prevMultiScan != null && prevMultiScan.fullScannedBoard != null
                        && prevMultiScan.fullScannedBoard.containsKey(cid))
                        ? prevMultiScan.fullScannedBoard.get(cid)
                        : ((currentBoard != null && cid < currentBoard.length && currentBoard[cid] != null
                                && !currentBoard[cid].isLocked()) ? currentBoard[cid].layers : null);
                boolean wasOcc = prevMultiScan != null && prevMultiScan.occludedCellBlockers != null
                        && prevMultiScan.occludedCellBlockers.containsKey(cid);
                List<Layer> candL = (gcs != null && !gcs.isLocked()) ? gcs.layers : Collections.emptyList();
                boolean refHas = refL != null && !refL.isEmpty();
                boolean candHas = candL != null && !candL.isEmpty();
                if (refHas && candHas) {
                    int rTop = refL.get(refL.size() - 1).colour;
                    int cTop = candL.get(candL.size() - 1).colour;
                    if (rTop == cTop) sc += 5.0;
                    else sc -= (wasOcc ? 0.25 : 2.2);
                } else if (!refHas && !candHas) {
                    sc += 1.6;
                } else {
                    sc -= (wasOcc ? 0.15 : 1.5);
                }
            }
            if (sc > bestExactScore) {
                bestExactScore = sc;
                bestRot = rot;
                bestVo = cand;
            }
        }
        if (detectedRotOut != null && detectedRotOut.length > 0) detectedRotOut[0] = bestRot;
        return bestVo;
    }

    private static final class CausalCandidate {
        CellState[] board;
        int score;
        String diagnosisRu;
        double rankScore;
        int diffCellsFromApp;
    }

    private static double computeThumbMad(int[] a, int[] b) {
        if (a == null || b == null || a.length != b.length || a.length == 0) return 999.0;
        double sum = 0.0;
        for (int i = 0; i < a.length; i++) {
            int ca = a[i], cb = b[i];
            sum += (Math.abs(((ca >> 16) & 0xFF) - ((cb >> 16) & 0xFF))
                    + Math.abs(((ca >> 8) & 0xFF) - ((cb >> 8) & 0xFF))
                    + Math.abs((ca & 0xFF) - (cb & 0xFF))) / 3.0;
        }
        return sum / a.length;
    }

    private static CausalCandidate evaluateCausalCandidate(
            CellState[] candBoard, int candScore, String diagnosisRu, double priorBonus,
            StageCfg stgCfg, int effScreenScore, boolean hasExactScreenScore,
            Map<Integer, List<Layer>> fusedScanMap,
            Map<Integer, Integer> occludedBlockers,
            VisionOutput bvo, int detectedRot,
            CausalHistoryStep lastZeroSnap,
            CellState[] currentAppBoard
    ) {
        if (candBoard == null) return null;
        if (hasExactScreenScore && Math.abs(candScore - effScreenScore) > 2) {
            return null;
        }
        int occMismatches = 0;
        int topColMismatches = 0;
        double heightPenalty = 0.0;
        double deltaPenalty = 0.0;
        int diffCellsFromApp = 0;

        for (StageCellCfg scItem : stgCfg.cells) {
            int cid = scItem.cellId;
            CellState cCs = candBoard[cid];
            CellState aCs = (currentAppBoard != null && cid < currentAppBoard.length) ? currentAppBoard[cid] : null;
            if (aCs != null && cCs != null && !layersEqual(aCs.layers, cCs.layers)) {
                diffCellsFromApp++;
            }
            if (bvo.popupCovered != null && bvo.popupCovered[cid]) continue;
            if (scItem.unlockCellScore > candScore && scItem.unlockCellScore > effScreenScore) continue;

            List<Layer> obsL = fusedScanMap.get(cid);
            boolean obsOcc = obsL != null && !obsL.isEmpty();
            boolean candOcc = cCs != null && !cCs.isLocked() && cCs.hasBlocks();
            boolean isOccluded = occludedBlockers != null && occludedBlockers.containsKey(cid);

            if (candOcc != obsOcc) {
                // Allow a short (<=3 block) stack to be completely hidden behind a tall (>=6 block) blocker at single angle
                if (candOcc && !obsOcc && isOccluded && sumLayerBlocks(cCs.layers) <= 3) {
                    heightPenalty += 1.5;
                } else {
                    occMismatches++;
                }
                continue;
            }
            if (candOcc && obsOcc) {
                int candTop = cCs.topColor();
                int obsTop = obsL.get(obsL.size() - 1).colour;
                if (candTop != obsTop) {
                    boolean sliverRimAlias = isOccluded && sumLayerBlocks(cCs.layers) <= 4
                            && ((candTop == 2 && (obsTop == 1 || obsTop == 3))
                            || (candTop == 1 && (obsTop == 2 || obsTop == 8))
                            || (candTop == 3 && obsTop == 2)
                            || (candTop == 5 && obsTop == 6)
                            || (candTop == 6 && obsTop == 5));
                    if (sliverRimAlias) {
                        heightPenalty += 2.2;
                    } else {
                        topColMismatches++;
                    }
                    continue;
                }
                double candTot = Math.min(12.0, sumLayerBlocks(cCs.layers));
                double obsH = (detectedRot == 0 && bvo.measuredHeightSlices != null && bvo.measuredHeightSlices[cid] > 0.5f)
                        ? bvo.measuredHeightSlices[cid]
                        : sumLayerBlocks(obsL);
                double hErr = Math.abs(candTot - obsH);
                heightPenalty += (isOccluded ? 0.35 : 0.90) * Math.min(5.0, hErr);

                if (detectedRot == 0 && lastZeroSnap != null && lastZeroSnap.measuredRotDeg == 0
                        && lastZeroSnap.boardAfter != null && lastZeroSnap.measuredHeightSlices != null) {
                    double prevSimH = Math.min(12.0, sumLayerBlocks(lastZeroSnap.boardAfter[cid].layers));
                    double simDelta = candTot - prevSimH;
                    double obsDelta = obsH - lastZeroSnap.measuredHeightSlices[cid];
                    deltaPenalty += 0.85 * Math.min(5.0, Math.abs(simDelta - obsDelta));
                }
            }
        }

        if (occMismatches > 0 || topColMismatches > 0) return null;

        double scoreBonus = 0.0;
        if (hasExactScreenScore) {
            scoreBonus = (candScore == effScreenScore) ? 45.0 : (-14.0 * Math.abs(candScore - effScreenScore));
        }
        CausalCandidate cc = new CausalCandidate();
        cc.board = candBoard;
        cc.score = candScore;
        cc.diagnosisRu = diagnosisRu;
        cc.diffCellsFromApp = diffCellsFromApp;
        cc.rankScore = scoreBonus + priorBonus - heightPenalty - deltaPenalty - 0.15 * diffCellsFromApp;
        return cc;
    }

    /**
     * Deduces the most probable game-physics cause of board changes using move/hand/plan history
     * and multi-screenshot height/appearance deltas.
     */
    private static CausalCandidate deduceCausalReconciliation(
            StageCfg stgCfg,
            CellState[] currentBoard, int curAppScore,
            Map<Integer, List<Layer>> currentWaits, SolvePlan currentLastPlan,
            List<CausalHistoryStep> history,
            int screenScore, int effScore, int detectedRot,
            Map<Integer, List<Layer>> fusedScanMap,
            Map<Integer, Integer> occludedBlockers,
            VisionOutput bvo
    ) {
        boolean hasExactScreenScore = (screenScore >= 0);
        CausalHistoryStep lastZeroSnap = null;
        if (history != null) {
            for (int i = history.size() - 1; i >= 0; i--) {
                CausalHistoryStep hs = history.get(i);
                if (hs != null && hs.measuredRotDeg == 0 && hs.cellThumbRgb != null) {
                    lastZeroSnap = hs;
                    break;
                }
            }
        }

        CausalCandidate best = null;

        // H0: Current app board already matches screen
        if (currentBoard != null) {
            CausalCandidate c0 = evaluateCausalCandidate(
                    cloneBoard(currentBoard), curAppScore,
                    "✅ Поле полностью совпадает с игрой", 4.5,
                    stgCfg, effScore, hasExactScreenScore, fusedScanMap, occludedBlockers,
                    bvo, detectedRot, lastZeroSnap, currentBoard
            );
            if (c0 != null) best = c0;
        }

        // H1: 1, 2, or 3 cards from currentWaits played on currentBoard (including wrong target cell!)
        if (currentBoard != null && currentWaits != null) {
            // H1a: Exact prefixes of currentLastPlan
            if (currentLastPlan != null && currentLastPlan.steps != null) {
                for (int k = 1; k <= currentLastPlan.steps.size(); k++) {
                    StepMove sm = currentLastPlan.steps.get(k - 1);
                    String lbl = (k == currentLastPlan.steps.size() && k == 3)
                            ? "💡 Ходы раунда уже выполнены в игре"
                            : ("💡 Выполнен " + (k == 1 ? ("Шаг 1 (Слот #" + sm.slotId + " → #" + sm.targetCellId + ")")
                                    : ("Шаги 1–" + k + " плана")));
                    CausalCandidate ck = evaluateCausalCandidate(
                            cloneBoard(sm.boardAfter), sm.scoreAfter, lbl, 3.8,
                            stgCfg, effScore, hasExactScreenScore, fusedScanMap, occludedBlockers,
                            bvo, detectedRot, lastZeroSnap, currentBoard
                    );
                    if (ck != null && (best == null || ck.rankScore > best.rankScore)) best = ck;
                }
            }

            // H1b: Any 1 card from currentWaits placed onto any empty cell c (e.g. misplaced card #30 -> #1!)
            Map<Integer, Integer> plannedTargetOfSlot = new HashMap<>();
            if (currentLastPlan != null && currentLastPlan.steps != null) {
                for (StepMove sm : currentLastPlan.steps) plannedTargetOfSlot.put(sm.slotId, sm.targetCellId);
            }
            for (int sid = 1; sid <= 3; sid++) {
                List<Layer> card = currentWaits.get(sid);
                if (card == null || card.isEmpty()) continue;
                for (StageCellCfg scItem : stgCfg.cells) {
                    int cid = scItem.cellId;
                    if (currentBoard[cid] == null || !currentBoard[cid].isEmptyPlaceable()) continue;
                    SimResult sim = simulatePlaceCard(currentBoard, card, cid, curAppScore);
                    Integer pTgt = plannedTargetOfSlot.get(sid);
                    String lbl = (pTgt != null && pTgt != cid)
                            ? ("💡 Слот #" + sid + " поставлен в #" + cid + " (вместо #" + pTgt + ")")
                            : ("💡 Поставлен Слот #" + sid + " → #" + cid);
                    CausalCandidate c1 = evaluateCausalCandidate(
                            sim.newCells, sim.finalScore, lbl, 3.3,
                            stgCfg, effScore, hasExactScreenScore, fusedScanMap, occludedBlockers,
                            bvo, detectedRot, lastZeroSnap, currentBoard
                    );
                    if (c1 != null && (best == null || c1.rankScore > best.rankScore)) best = c1;
                }
            }

            // H1c: 2 cards from currentWaits placed onto cells (only if 0/1-card hypotheses didn't already explain the board!)
            int[][] slotPairs = {{1, 2}, {2, 1}, {1, 3}, {3, 1}, {2, 3}, {3, 2}};
            if (best == null || (hasExactScreenScore && best.score != effScore)) for (int[] sp : slotPairs) {
                List<Layer> cardA = currentWaits.get(sp[0]);
                List<Layer> cardB = currentWaits.get(sp[1]);
                if (cardA == null || cardA.isEmpty() || cardB == null || cardB.isEmpty()) continue;
                for (StageCellCfg scA : stgCfg.cells) {
                    int cidA = scA.cellId;
                    if (currentBoard[cidA] == null || !currentBoard[cidA].isEmptyPlaceable()) continue;
                    SimResult simA = simulatePlaceCard(currentBoard, cardA, cidA, curAppScore);
                    for (StageCellCfg scB : stgCfg.cells) {
                        int cidB = scB.cellId;
                        if (simA.newCells[cidB] == null || !simA.newCells[cidB].isEmptyPlaceable()) continue;
                        SimResult simB = simulatePlaceCard(simA.newCells, cardB, cidB, simA.finalScore);
                        if (hasExactScreenScore && Math.abs(simB.finalScore - effScore) > 2) continue;
                        String lbl = "💡 Выполнены 2 хода: #" + sp[0] + "→#" + cidA + ", #" + sp[1] + "→#" + cidB;
                        CausalCandidate c2 = evaluateCausalCandidate(
                                simB.newCells, simB.finalScore, lbl, 2.4,
                                stgCfg, effScore, hasExactScreenScore, fusedScanMap, occludedBlockers,
                                bvo, detectedRot, lastZeroSnap, currentBoard
                        );
                        if (c2 != null && (best == null || c2.rankScore > best.rankScore)) best = c2;
                    }
                }
            }
        }

        // H2: Hammer booster smashed 1 occupied stack on currentBoard (e.g. #96 Hammer on #9!)
        if (currentBoard != null) {
            for (StageCellCfg scItem : stgCfg.cells) {
                int hCid = scItem.cellId;
                if (currentBoard[hCid] == null || currentBoard[hCid].isLocked() || !currentBoard[hCid].hasBlocks()) continue;
                CellState[] hBoard = cloneBoard(currentBoard);
                hBoard[hCid].layers.clear();
                String lbl = "💡 🔨 Молоток очистил #" + hCid;
                CausalCandidate ch = evaluateCausalCandidate(
                        hBoard, curAppScore, lbl, 3.5,
                        stgCfg, effScore, hasExactScreenScore, fusedScanMap, occludedBlockers,
                        bvo, detectedRot, lastZeroSnap, currentBoard
                );
                if (ch != null && (best == null || ch.rankScore > best.rankScore)) best = ch;
            }
        }

        // H3 & H4: Inspect recent move rounds in history (wrong order, misplaced card in round, unplayed round, or ±1..2 count drift!)
        // Skip multi-round history replay if H0/H1/H2 already found a high-confidence exact causal match!
        if (history != null && !history.isEmpty()
                && (best == null || (hasExactScreenScore && best.score != effScore) || best.rankScore < 38.0)) {
            int minIdx = Math.max(0, history.size() - 3);
            for (int hi = history.size() - 1; hi >= minIdx; hi--) {
                CausalHistoryStep hs = history.get(hi);
                if (hs == null || hs.boardBefore == null) continue;

                // H3a: Round not yet played in game (accidental ⏩ Шаг+Рука)
                CausalCandidate cUndo = evaluateCausalCandidate(
                        cloneBoard(hs.boardBefore), hs.scoreBefore,
                        "💡 Прошлый раунд ещё не выполнен в игре", 3.1,
                        stgCfg, effScore, hasExactScreenScore, fusedScanMap, occludedBlockers,
                        bvo, detectedRot, lastZeroSnap, currentBoard
                );
                if (cUndo != null && (best == null || cUndo.rankScore > best.rankScore)) best = cUndo;

                if (hs.planBefore == null || hs.planBefore.steps == null || hs.planBefore.steps.isEmpty()
                        || hs.handBefore == null) {
                    continue;
                }
                List<StepMove> pSteps = hs.planBefore.steps;

                // H3b: Partial or full execution of hs.planBefore (even if player pressed ✋ Рука before ? Сверить, like #113->#114->#115!)
                for (int k = 1; k <= pSteps.size(); k++) {
                    StepMove sm = pSteps.get(k - 1);
                    String lbl = (k == 1)
                            ? ("💡 Выполнен Шаг 1 (Слот #" + sm.slotId + " → #" + sm.targetCellId + ")")
                            : ("💡 Выполнены Шаги 1–" + k + " плана");
                    CausalCandidate cPart = evaluateCausalCandidate(
                            cloneBoard(sm.boardAfter), sm.scoreAfter, lbl, 3.2,
                            stgCfg, effScore, hasExactScreenScore, fusedScanMap, occludedBlockers,
                            bvo, detectedRot, lastZeroSnap, currentBoard
                    );
                    if (cPart != null && (best == null || cPart.rankScore > best.rankScore)) best = cPart;
                }

                // H3b2: Single card from hs.handBefore placed onto any empty cell on hs.boardBefore
                for (int sid = 1; sid <= 3; sid++) {
                    List<Layer> card = hs.handBefore.get(sid);
                    if (card == null || card.isEmpty()) continue;
                    for (StageCellCfg scItem : stgCfg.cells) {
                        int cid = scItem.cellId;
                        if (hs.boardBefore[cid] == null || !hs.boardBefore[cid].isEmptyPlaceable()) continue;
                        SimResult sim = simulatePlaceCard(hs.boardBefore, card, cid, hs.scoreBefore);
                        if (hasExactScreenScore && Math.abs(sim.finalScore - effScore) > 2) continue;
                        String lbl = "💡 Поставлен Слот #" + sid + " → #" + cid;
                        CausalCandidate cH1 = evaluateCausalCandidate(
                                sim.newCells, sim.finalScore, lbl, 3.05,
                                stgCfg, effScore, hasExactScreenScore, fusedScanMap, occludedBlockers,
                                bvo, detectedRot, lastZeroSnap, currentBoard
                        );
                        if (cH1 != null && (best == null || cH1.rankScore > best.rankScore)) best = cH1;
                    }
                }

                // H3c: Permuted step order & single-card target misclicks on the 3-step round!
                // Only run expensive 3-step permutation/misclick search if no simpler exact causal explanation (H0/H1/H2/H3a/H3b) matched!
                if (pSteps.size() >= 2 && (best == null || (hasExactScreenScore && best.score != effScore))) {
                    int nS = pSteps.size();
                    int[][] perms = (nS == 3)
                            ? new int[][]{{0, 1, 2}, {0, 2, 1}, {1, 0, 2}, {1, 2, 0}, {2, 0, 1}, {2, 1, 0}}
                            : new int[][]{{0, 1}, {1, 0}};
                    for (int pi = 0; pi < perms.length; pi++) {
                        int[] perm = perms[pi];
                        if (pi > 0) {
                            // Pure order swap on the same planned target cells
                            CellState[] b = cloneBoard(hs.boardBefore);
                            int sc = hs.scoreBefore;
                            boolean legal = true;
                            StringBuilder ordSb = new StringBuilder();
                            for (int idx : perm) {
                                StepMove sm = pSteps.get(idx);
                                if (b[sm.targetCellId] == null || !b[sm.targetCellId].isEmptyPlaceable()) {
                                    legal = false;
                                    break;
                                }
                                SimResult sr = simulatePlaceCard(b, sm.cardLayers, sm.targetCellId, sc);
                                b = sr.newCells;
                                sc = sr.finalScore;
                                if (ordSb.length() > 0) ordSb.append("→");
                                ordSb.append("#").append(sm.slotId);
                            }
                            if (legal) {
                                CausalCandidate cPerm = evaluateCausalCandidate(
                                        b, sc, "💡 Пачки поставлены в порядке " + ordSb + " (вместо плана)", 2.9,
                                        stgCfg, effScore, hasExactScreenScore, fusedScanMap, occludedBlockers,
                                        bvo, detectedRot, lastZeroSnap, currentBoard
                                );
                                if (cPerm != null && (best == null || cPerm.rankScore > best.rankScore)) best = cPerm;
                            }
                        }

                        // Single-step wrong cell in this ordering: vary which step mIdx went to an alternate cell altCid
                        for (int mIdx = 0; mIdx < nS; mIdx++) {
                            CellState[] prefB = cloneBoard(hs.boardBefore);
                            int prefSc = hs.scoreBefore;
                            boolean prefOk = true;
                            for (int q = 0; q < mIdx; q++) {
                                StepMove sm = pSteps.get(perm[q]);
                                if (prefB[sm.targetCellId] == null || !prefB[sm.targetCellId].isEmptyPlaceable()) {
                                    prefOk = false;
                                    break;
                                }
                                SimResult sr = simulatePlaceCard(prefB, sm.cardLayers, sm.targetCellId, prefSc);
                                prefB = sr.newCells;
                                prefSc = sr.finalScore;
                            }
                            if (!prefOk) continue;

                            StepMove errSm = pSteps.get(perm[mIdx]);
                            for (StageCellCfg altItem : stgCfg.cells) {
                                int altCid = altItem.cellId;
                                if (altCid == errSm.targetCellId) continue;
                                if (prefB[altCid] == null || !prefB[altCid].isEmptyPlaceable()) continue;
                                SimResult srMid = simulatePlaceCard(prefB, errSm.cardLayers, altCid, prefSc);
                                CellState[] curB = srMid.newCells;
                                int curSc = srMid.finalScore;
                                boolean tailOk = true;
                                for (int q = mIdx + 1; q < nS; q++) {
                                    StepMove sm = pSteps.get(perm[q]);
                                    if (curB[sm.targetCellId] == null || !curB[sm.targetCellId].isEmptyPlaceable()) {
                                        tailOk = false;
                                        break;
                                    }
                                    SimResult sr = simulatePlaceCard(curB, sm.cardLayers, sm.targetCellId, curSc);
                                    curB = sr.newCells;
                                    curSc = sr.finalScore;
                                }
                                if (!tailOk) continue;
                                if (hasExactScreenScore && Math.abs(curSc - effScore) > 2) continue;
                                String lbl = "💡 Слот #" + errSm.slotId + " поставлен в #" + altCid
                                        + " (вместо #" + errSm.targetCellId + ")";
                                CausalCandidate cAlt = evaluateCausalCandidate(
                                        curB, curSc, lbl, (pi == 0 ? 3.1 : 2.6),
                                        stgCfg, effScore, hasExactScreenScore, fusedScanMap, occludedBlockers,
                                        bvo, detectedRot, lastZeroSnap, currentBoard
                                );
                                if (cAlt != null && (best == null || cAlt.rankScore > best.rankScore)) best = cAlt;
                            }
                        }
                    }
                }

                // H4: ±1..2 chip count drift on a pre-existing stack before the merge round (e.g. #103 Berry×9 on #9!)
                for (StageCellCfg mItem : stgCfg.cells) {
                    int mCid = mItem.cellId;
                    CellState mCs = hs.boardBefore[mCid];
                    if (mCs == null || mCs.isLocked() || !mCs.hasBlocks()) continue;
                    int topIdx = mCs.layers.size() - 1;
                    for (int delta : new int[]{-1, 1, -2, 2}) {
                        int newCnt = mCs.layers.get(topIdx).count + delta;
                        if (newCnt < 1 || newCnt > 9) continue;
                        CellState[] modB = cloneBoard(hs.boardBefore);
                        modB[mCid].layers.get(topIdx).count = newCnt;
                        modB[mCid].normalize();
                        int curSc = hs.scoreBefore;
                        boolean ok = true;
                        for (StepMove sm : pSteps) {
                            if (modB[sm.targetCellId] == null || !modB[sm.targetCellId].isEmptyPlaceable()) {
                                ok = false;
                                break;
                            }
                            SimResult sr = simulatePlaceCard(modB, sm.cardLayers, sm.targetCellId, curSc);
                            modB = sr.newCells;
                            curSc = sr.finalScore;
                        }
                        if (!ok) continue;
                        if (hasExactScreenScore && curSc != effScore) continue;
                        String lbl = "💡 Уточнён остаток после слияния с #" + mCid + " (счёт " + curSc + ")";
                        CausalCandidate cDrift = evaluateCausalCandidate(
                                modB, curSc, lbl, (Math.abs(delta) == 1 ? 2.8 : 2.3),
                                stgCfg, effScore, hasExactScreenScore, fusedScanMap, occludedBlockers,
                                bvo, detectedRot, lastZeroSnap, currentBoard
                        );
                        if (cDrift != null && (best == null || cDrift.rankScore > best.rankScore)) best = cDrift;
                    }
                }
            }
        }
        return best;
    }

    public static BoardDiffResult computeBoardDiffFromBitmap(
            Bitmap rawBmp,
            CellState[] currentBoard,
            int curStageNo,
            int curScore,
            int rotationDeg,
            Calibration userCalib,
            Map<Integer, int[]> customPalette,
            Map<Integer, StageCfg> allStages
    ) {
        return computeOrMergeBoardDiffFromBitmap(
                rawBmp, currentBoard, curStageNo, curScore, rotationDeg,
                userCalib, customPalette, allStages, null, null, null, null
        );
    }

    public static BoardDiffResult computeOrMergeBoardDiffFromBitmap(
            Bitmap rawBmp,
            CellState[] currentBoard,
            int curStageNo,
            int curScore,
            int prevRotationDeg,
            Calibration userCalib,
            Map<Integer, int[]> customPalette,
            Map<Integer, StageCfg> allStages,
            BoardDiffResult prevMultiScan
    ) {
        return computeOrMergeBoardDiffFromBitmap(
                rawBmp, currentBoard, curStageNo, curScore, prevRotationDeg,
                userCalib, customPalette, allStages, prevMultiScan, null, null, null
        );
    }

    public static BoardDiffResult computeOrMergeBoardDiffFromBitmap(
            Bitmap rawBmp,
            CellState[] currentBoard,
            int curStageNo,
            int curScore,
            int prevRotationDeg,
            Calibration userCalib,
            Map<Integer, int[]> customPalette,
            Map<Integer, StageCfg> allStages,
            BoardDiffResult prevMultiScan,
            Map<Integer, List<Layer>> currentWaits,
            SolvePlan currentLastPlan,
            List<CausalHistoryStep> causalHistory
    ) {
        Bitmap bmp = cropSplitScreenGamePane(rawBmp, customPalette, allStages);
        boolean croppedSplit = (bmp != rawBmp);
        VisionOutput preHh = analyzeHeaderAndHandFrame(bmp, customPalette, allStages);
        int origW = bmp.getWidth(), origH = bmp.getHeight();
        int workW = Math.min(540, origW);
        int workH = Math.max(180, Math.round((float) origH * workW / Math.max(1, origW)));
        Bitmap workBmp = (workW == origW && workH == origH) ? bmp : Bitmap.createScaledBitmap(bmp, workW, workH, true);
        int[] px = new int[workW * workH];
        workBmp.getPixels(px, 0, workW, 0, 0, workW, workH);
        if (workBmp != bmp) workBmp.recycle();
        if (croppedSplit) bmp.recycle();

        return computeOrMergeBoardDiffFromArgb(
                px, workW, workH, preHh, currentBoard, curStageNo, curScore, prevRotationDeg,
                userCalib, customPalette, allStages, prevMultiScan,
                currentWaits, currentLastPlan, causalHistory
        );
    }

    public static BoardDiffResult computeOrMergeBoardDiffFromArgb(
            int[] px, int workW, int workH,
            CellState[] currentBoard,
            int curStageNo,
            int curScore,
            int prevRotationDeg,
            Calibration userCalib,
            Map<Integer, int[]> customPalette,
            Map<Integer, StageCfg> allStages,
            BoardDiffResult prevMultiScan,
            Map<Integer, List<Layer>> currentWaits,
            SolvePlan currentLastPlan,
            List<CausalHistoryStep> causalHistory
    ) {
        return computeOrMergeBoardDiffFromArgb(
                px, workW, workH, null, currentBoard, curStageNo, curScore, prevRotationDeg,
                userCalib, customPalette, allStages, prevMultiScan,
                currentWaits, currentLastPlan, causalHistory
        );
    }

    /**
     * Pure-ARGB History-Aware & Multi-Angle Board Reconciliation Engine (v1.0.30):
     * 1) Pre-scan automatic 6-angle rotation & direction detection BEFORE cell analysis on EVERY scan.
     * 2) Multi-screenshot 5x5 patch & continuous stack-height stability filter.
     * 3) Causal forward-simulation solver (misplaced card, wrong step order, partial hand, Hammer, ±1..2 merge drift).
     * 4) Minimalist turn-icon guidance (zero verbose occlusion text walls).
     */
    public static BoardDiffResult computeOrMergeBoardDiffFromArgb(
            int[] px, int workW, int workH,
            VisionOutput preHeaderHand,
            CellState[] currentBoard,
            int curStageNo,
            int curScore,
            int prevRotationDeg,
            Calibration userCalib,
            Map<Integer, int[]> customPalette,
            Map<Integer, StageCfg> allStages,
            BoardDiffResult prevMultiScan,
            Map<Integer, List<Layer>> currentWaits,
            SolvePlan currentLastPlan,
            List<CausalHistoryStep> causalHistory
    ) {
        int stgNo = (allStages != null && allStages.containsKey(curStageNo)) ? curStageNo : 1;
        StageCfg stgCfg = (allStages != null && allStages.containsKey(stgNo))
                ? allStages.get(stgNo) : allStages.values().iterator().next();

        if (isBlankOrNonGameFrame(px, workW, workH)) {
            if (prevMultiScan != null && prevMultiScan.stageNo == stgNo) {
                prevMultiScan.invalidFrame = true;
                prevMultiScan.occlusionGuidanceRu = "⚠ Экран игры не виден (чёрный кадр)";
                return prevMultiScan;
            }
            BoardDiffResult bad = new BoardDiffResult();
            bad.stageNo = stgNo;
            bad.appScore = curScore;
            bad.detectedScore = curScore;
            bad.rotationDeg = (((prevRotationDeg % 360) + 360) % 360);
            bad.invalidFrame = true;
            bad.occlusionGuidanceRu = "⚠ Экран игры не виден — откройте игру и повторите";
            return bad;
        }

        VisionOutput hh = (preHeaderHand != null)
                ? preHeaderHand
                : analyzeHeaderAndHandArgb(px, workW, workH, customPalette, allStages);
        int rawDetScore = hh.detectedScore;
        // Sanity-guard OCR score against huge implausible single-step jumps (>80 pts) when curScore is established
        int initEffScore = (rawDetScore >= 0 && (curScore == 0 || (rawDetScore >= curScore - 30 && rawDetScore <= curScore + 85)))
                ? rawDetScore : curScore;
        boolean mergeMode = (prevMultiScan != null && prevMultiScan.stageNo == stgNo
                && !prevMultiScan.invalidFrame && !prevMultiScan.fullScannedBoard.isEmpty());

        // Step 1: Pre-scan automatic 6-angle orientation & turn-direction lock BEFORE cell diffing!
        int[] rotBox = new int[1];
        VisionOutput bvo = detectBoardAnglePreScan(
                px, workW, workH, stgCfg, curScore, prevRotationDeg,
                mergeMode ? prevMultiScan : null, currentBoard,
                userCalib, customPalette, allStages, rotBox
        );
        int detectedRot = rotBox[0];

        // Cross-check OCR score against visible lock tiles in BOTH directions:
        // (1) OCR read too high ("128"->"378") if a lock cell <= rawDetScore is still locked;
        // (2) OCR dropped a digit ("564"->"56") if a lock cell > rawDetScore visibly holds blocks!
        int validatedScreenScore = rawDetScore;
        if (validatedScreenScore >= 0) {
            for (StageCellCfg scItem : stgCfg.cells) {
                if (scItem.unlockCellScore > 0) {
                    CellState gcs = bvo.cells[scItem.cellId];
                    if (validatedScreenScore >= scItem.unlockCellScore && gcs != null && gcs.isLocked()) {
                        validatedScreenScore = -1;
                        break;
                    }
                    if (validatedScreenScore < scItem.unlockCellScore && gcs != null && !gcs.isLocked() && gcs.hasBlocks()) {
                        validatedScreenScore = -1;
                        break;
                    }
                }
            }
            if (validatedScreenScore >= 0 && curScore > 0 && (validatedScreenScore < curScore - 35 || validatedScreenScore > curScore + 85)) {
                validatedScreenScore = -1;
            }
        }
        int effScore = validatedScreenScore >= 0 ? validatedScreenScore : curScore;

        BoardDiffResult out = new BoardDiffResult();
        out.stageNo = stgNo;
        out.appScore = curScore;
        out.detectedScore = validatedScreenScore;
        out.rotationDeg = detectedRot;
        out.scannedWaits = hh.waits;
        out.latestVisionOutput = bvo;
        if (bvo.popupCovered != null) {
            for (StageCellCfg scItem : stgCfg.cells) {
                if (bvo.popupCovered[scItem.cellId]) {
                    out.hasPopupObstruction = true;
                    break;
                }
            }
        }

        if (mergeMode) {
            out.scannedAngles.addAll(prevMultiScan.scannedAngles);
            out.cleanCells.addAll(prevMultiScan.cleanCells);
            for (Map.Entry<Integer, List<Layer>> e : prevMultiScan.fullScannedBoard.entrySet()) {
                List<Layer> cp = new ArrayList<>();
                for (Layer l : e.getValue()) cp.add(l.copy());
                out.fullScannedBoard.put(e.getKey(), cp);
            }
            if (prevMultiScan.scannedAngles.contains(detectedRot)) {
                out.sameAngleNotRotated = true;
            }
        }
        out.scannedAngles.add(detectedRot);

        // Build fresh angle map and merged height map for accurate occlusion raycasting
        Map<Integer, List<Layer>> freshAngleMap = new HashMap<>();
        Map<Integer, List<Layer>> heightRefMap = new HashMap<>();
        for (StageCellCfg scItem : stgCfg.cells) {
            int cid = scItem.cellId;
            if (bvo.popupCovered != null && bvo.popupCovered[cid]) {
                List<Layer> keep = out.fullScannedBoard.containsKey(cid)
                        ? out.fullScannedBoard.get(cid)
                        : ((currentBoard != null && cid < currentBoard.length && currentBoard[cid] != null)
                                ? currentBoard[cid].layers : Collections.emptyList());
                freshAngleMap.put(cid, keep);
                heightRefMap.put(cid, keep);
                continue;
            }
            List<Layer> fl = new ArrayList<>();
            if (bvo.cells[cid] != null && !bvo.cells[cid].isLocked()) {
                for (Layer l : bvo.cells[cid].layers) fl.add(l.copy());
            }
            freshAngleMap.put(cid, fl);
            if (scItem.unlockCardScore > effScore && scItem.initLayers != null && !scItem.initLayers.isEmpty()) {
                // Physical 8-12 chip locked rainbow tower occludes cells behind it even while locked!
                heightRefMap.put(cid, scItem.initLayers);
            } else {
                List<Layer> prevL = out.fullScannedBoard.get(cid);
                heightRefMap.put(cid, (sumLayerBlocks(prevL) > sumLayerBlocks(fl)) ? prevL : fl);
            }
        }

        Set<Integer> newlyCleared = new HashSet<>();
        for (StageCellCfg scItem : stgCfg.cells) {
            int cid = scItem.cellId;
            if (bvo.popupCovered != null && bvo.popupCovered[cid]) continue;
            int blockerAtNewAngle = findFrontBlockerAtAngle(stgCfg, detectedRot, cid, heightRefMap);
            if (!mergeMode) {
                out.fullScannedBoard.put(cid, freshAngleMap.get(cid));
                if (blockerAtNewAngle < 0) {
                    out.cleanCells.add(cid);
                } else {
                    out.occludedCellBlockers.put(cid, blockerAtNewAngle);
                }
            } else {
                if (blockerAtNewAngle < 0) {
                    if (!out.cleanCells.contains(cid)) {
                        newlyCleared.add(cid);
                    }
                    out.fullScannedBoard.put(cid, freshAngleMap.get(cid));
                    out.cleanCells.add(cid);
                } else if (!out.cleanCells.contains(cid)) {
                    out.occludedCellBlockers.put(cid, blockerAtNewAngle);
                }
            }
        }

        CellState[] baseCmp = (currentBoard != null)
                ? currentBoard : createStageBoard(stgCfg, effScore);

        // Step 2: Run History-Aware Causal Reconciliation Engine!
        int occupiedOnApp = 0;
        for (StageCellCfg scItem : stgCfg.cells) {
            CellState cs = baseCmp[scItem.cellId];
            if (cs != null && !cs.isLocked() && cs.hasBlocks()) occupiedOnApp++;
        }
        CausalCandidate causalWin = (occupiedOnApp > 0)
                ? deduceCausalReconciliation(
                        stgCfg, baseCmp, curScore, currentWaits, currentLastPlan, causalHistory,
                        validatedScreenScore, effScore, detectedRot, out.fullScannedBoard, out.occludedCellBlockers, bvo
                )
                : null;

        if (causalWin != null) {
            out.causalExactMatch = true;
            out.causalDiagnosisRu = causalWin.diagnosisRu;
            if (causalWin.score >= 0) {
                out.detectedScore = causalWin.score;
            }
            for (StageCellCfg scItem : stgCfg.cells) {
                int cid = scItem.cellId;
                CellState appCs = baseCmp[cid];
                CellState solCs = causalWin.board[cid];
                boolean appLocked = appCs.lockScore > effScore || appCs.lockCardScore > effScore;
                boolean solLocked = solCs.lockScore > effScore || solCs.lockCardScore > effScore;
                if (appLocked && solLocked) continue;
                List<Layer> appL = new ArrayList<>();
                if (!appLocked) for (Layer l : appCs.layers) appL.add(l.copy());
                List<Layer> solL = new ArrayList<>();
                if (!solLocked) for (Layer l : solCs.layers) solL.add(l.copy());
                if (layersEqual(appL, solL)) continue;

                CellDiff diff = new CellDiff();
                diff.cellId = cid;
                diff.appLayers = appL;
                diff.gameLayers = solL;
                diff.selected = true;
                diff.occludedHint = false;
                diff.note = "✓ Точный расчёт по истории ходов";
                out.diffs.add(diff);
            }
            out.solutionActionLabelRu = out.diffs.isEmpty()
                    ? ""
                    : ("✓ Привести к игре (" + out.diffs.size() + " кл.)");
            populateOcclusionAdvice(out, stgCfg, prevRotationDeg);
            return out;
        }

        // Step 3: Multi-Frame Appearance/Height Stability Fallback (when divergence spans multiple unlogged turns)
        CausalHistoryStep lastZeroSnap = null;

        // Frame-to-frame pixel-delta filter: compute per-cell MAD from previous frame
        int[] frameDeltaMad = null;
        int frameDeltaEps = 0;
        AppState frameSt = AppState.get();
        if (frameSt != null && frameSt.hasPrevFrame && frameSt.prevFrameArgb != null
                && frameSt.prevFrameW == workW && frameSt.prevFrameH == workH) {
            // Build claimed[] mask using ScreenBoardGeometry cell centers
            int[] claimed = new int[workW * workH];
            ScreenBoardGeometry sg = (frameSt.screenGeo != null && frameSt.screenGeo.stageNo == stgNo)
                    ? frameSt.screenGeo
                    : createDefaultScreenGeometry(stgCfg, detectedRot, workW, workH);
            for (int cid = 1; cid <= MAX_POS_COUNT; cid++) {
                float[] c = sg.getCellScreenCenter(cid, workW, workH);
                int cx = (int) c[0];
                int cy = (int) c[1];
                float rx = c[2];
                int r = (int) (rx * 0.85);
                int y0 = Math.max(0, cy - r);
                int y1 = Math.min(workH - 1, cy + r);
                int x0 = Math.max(0, cx - r);
                int x1 = Math.min(workW - 1, cx + r);
                for (int y = y0; y <= y1; y++) {
                    for (int x = x0; x <= x1; x++) {
                        claimed[y * workW + x] = cid;
                    }
                }
            }
            frameDeltaMad = computeFrameDelta(px, workW, workH, frameSt.prevFrameArgb, frameSt.prevFrameW, frameSt.prevFrameH, claimed, MAX_POS_COUNT);
            // Calibrate eps from trusted cells (cells where CV reading is stable)
            boolean[] trusted = new boolean[MAX_POS_COUNT + 1];
            if (frameDeltaMad != null) {
                for (StageCellCfg scItem : stgCfg.cells) {
                    int cid = scItem.cellId;
                    if (frameDeltaMad[cid] > 0 && out.occludedCellBlockers.get(cid) == null
                            && bvo.cells[cid] != null && !bvo.cells[cid].isLocked()) {
                        trusted[cid] = true;
                    }
                }
                frameDeltaEps = calibrateFrameDeltaEps(frameDeltaMad, trusted, MAX_POS_COUNT);
            }
        }
        if (causalHistory != null) {
            for (int i = causalHistory.size() - 1; i >= 0; i--) {
                CausalHistoryStep hs = causalHistory.get(i);
                if (hs != null && hs.measuredRotDeg == 0 && hs.cellThumbRgb != null) {
                    lastZeroSnap = hs;
                    break;
                }
            }
        }
        boolean singleAngleOnly = (out.scannedAngles.size() <= 1);

        for (StageCellCfg scItem : stgCfg.cells) {
            int cid = scItem.cellId;
            if (bvo.popupCovered != null && bvo.popupCovered[cid]) continue;
            CellState appCs = (baseCmp != null && cid < baseCmp.length && baseCmp[cid] != null)
                    ? baseCmp[cid] : new CellState(cid, 1);
            CellState gameCs = bvo.cells[cid];

            boolean appLocked = appCs.lockScore > effScore || appCs.lockCardScore > effScore;
            boolean gameLocked = gameCs.isLocked();
            if (appLocked && gameLocked) continue;

            List<Layer> appL = new ArrayList<>();
            if (!appLocked) {
                for (Layer l : appCs.layers) appL.add(l.copy());
            }
            List<Layer> fusedGameL = out.fullScannedBoard.get(cid);
            List<Layer> gameL = new ArrayList<>();
            if (!gameLocked && fusedGameL != null) {
                for (Layer l : fusedGameL) gameL.add(l.copy());
            }

            Integer stillBlockedBy = out.occludedCellBlockers.get(cid);
            if (layersEqual(appL, gameL)) continue;

            // Frame-to-frame visual invariance check: if cell pixels & stack height did not change since last 0° scan
            // and occupancy + top color match appL, never emit false layer/slice noise!
            if (detectedRot == 0 && lastZeroSnap != null && lastZeroSnap.cellThumbRgb != null
                    && bvo.cellThumbRgb != null && lastZeroSnap.cellThumbRgb[cid] != null && bvo.cellThumbRgb[cid] != null) {
                double mad = computeThumbMad(bvo.cellThumbRgb[cid], lastZeroSnap.cellThumbRgb[cid]);
                float dH = (lastZeroSnap.measuredHeightSlices != null && bvo.measuredHeightSlices != null)
                        ? Math.abs(bvo.measuredHeightSlices[cid] - lastZeroSnap.measuredHeightSlices[cid]) : 99f;
                boolean sameOcc = (appL.isEmpty() == gameL.isEmpty());
                boolean sameTop = appL.isEmpty() || (!gameL.isEmpty()
                        && appL.get(appL.size() - 1).colour == gameL.get(gameL.size() - 1).colour);
                if (mad < 8.5 && dH < 0.85f && sameOcc && sameTop) {
                    continue;
                }
            }

            if (!appL.isEmpty() && !gameL.isEmpty()) {
                int appTopC = appL.get(appL.size() - 1).colour;
                int gameTopC = gameL.get(gameL.size() - 1).colour;
                int appTopN = appL.get(appL.size() - 1).count;
                int gameTopN = gameL.get(gameL.size() - 1).count;
                int appTot = sumLayerBlocks(appL);
                int gameTot = sumLayerBlocks(gameL);
                if (stillBlockedBy != null && appTopC == gameTopC && Math.abs(appTot - gameTot) <= 3) {
                    continue;
                }
                if (appTopC == gameTopC && Math.abs(appTot - gameTot) <= 2 && Math.abs(appTopN - gameTopN) <= 2) {
                    continue;
                }
                if (singleAngleOnly && stillBlockedBy == null && appTopC == gameTopC
                        && Math.abs(appTot - gameTot) <= 2 && (appL.size() >= 2 || gameL.size() >= 2)) {
                    continue;
                }
            }
            if (stillBlockedBy != null && !appL.isEmpty() && gameL.isEmpty() && sumLayerBlocks(appL) <= 4) {
                continue;
            }

            // Frame-to-frame pixel-delta suppression: if per-cell MAD is below calibrated eps
            // and the cell is not occluded, suppress the diff (likely unchanged, CV noise).
            // Never suppress occluded cells — pixel-delta is meaningless there.
            if (frameDeltaMad != null && frameDeltaEps > 0 && detectedRot == 0) {
                boolean occluded = (stillBlockedBy != null);
                int cellMad = (cid < frameDeltaMad.length) ? frameDeltaMad[cid] : 0;
                if (shouldSuppressByFrameDelta(cid, cellMad, frameDeltaEps, occluded)) {
                    continue;
                }
            }

            CellDiff diff = new CellDiff();
            diff.cellId = cid;
            diff.appLayers = appL;
            diff.gameLayers = gameL;
            diff.selected = true;
            if (stillBlockedBy != null) {
                diff.occludedHint = true;
                diff.occludedByCellId = stillBlockedBy;
            } else if (newlyCleared.contains(cid)) {
                diff.note = "✓ Открыто (" + detectedRot + "°)";
            }
            out.diffs.add(diff);
        }

        Collections.sort(out.diffs, (a, b) -> {
            if (a.selected != b.selected) return a.selected ? -1 : 1;
            return Integer.compare(a.cellId, b.cellId);
        });
        out.causalDiagnosisRu = out.diffs.isEmpty()
                ? "✅ Поле совпадает с игрой"
                : ("💡 Изменилось клеток: " + out.diffs.size() + " — обновить по скану");
        out.solutionActionLabelRu = out.diffs.isEmpty()
                ? ""
                : ("✓ Привести к игре (" + out.diffs.size() + " кл.)");
        populateOcclusionAdvice(out, stgCfg, prevRotationDeg);
        return out;
    }

    public static JSONObject boardDiffToJson(BoardDiffResult res) {
        JSONObject root = new JSONObject();
        try {
            if (res == null) {
                root.put("hasPending", false);
                root.put("diffs", new JSONArray());
                return root;
            }
            root.put("hasPending", true);
            root.put("stageNo", res.stageNo);
            root.put("appScore", res.appScore);
            root.put("detectedScore", res.detectedScore);
            root.put("rotationDeg", res.rotationDeg);
            root.put("invalidFrame", res.invalidFrame);
            root.put("sameAngleNotRotated", res.sameAngleNotRotated);
            root.put("hasPopupObstruction", res.hasPopupObstruction);
            root.put("causalExactMatch", res.causalExactMatch);
            root.put("turnIconOnly", res.turnIconOnly != null ? res.turnIconOnly : "");
            root.put("detectedTurnBadge", res.detectedTurnBadge != null ? res.detectedTurnBadge : "");
            root.put("causalDiagnosisRu", res.causalDiagnosisRu != null ? res.causalDiagnosisRu : "");
            root.put("solutionActionLabelRu", res.solutionActionLabelRu != null ? res.solutionActionLabelRu : "");
            root.put("anglesScannedCount", res.scannedAngles.size());
            root.put("occludedCount", res.occludedCellBlockers.size());
            root.put("recommendedTurnDeltaDeg", res.recommendedTurnDeltaDeg);
            root.put("recommendedTargetRotDeg", res.recommendedTargetRotDeg);
            root.put("multiAngleAllClear", res.multiAngleAllClear);
            root.put("occlusionGuidanceRu", res.occlusionGuidanceRu != null ? res.occlusionGuidanceRu : "");
            root.put("lastScreenId", res.lastScreenId != null ? res.lastScreenId : "");
            root.put("uiScreenId", res.uiScreenId != null ? res.uiScreenId : "");

            JSONArray occArr = new JSONArray();
            for (Map.Entry<Integer, Integer> e : res.occludedCellBlockers.entrySet()) {
                JSONObject oj = new JSONObject();
                oj.put("cellId", e.getKey());
                oj.put("blockedBy", e.getValue());
                occArr.put(oj);
            }
            root.put("occludedCells", occArr);

            JSONObject waitsMap = new JSONObject();
            for (int sid = 1; sid <= 3; sid++) {
                JSONArray la = new JSONArray();
                List<Layer> wl = res.scannedWaits != null ? res.scannedWaits.get(sid) : null;
                if (wl != null) {
                    for (Layer l : wl) {
                        JSONObject lo = new JSONObject();
                        lo.put("colour", l.colour);
                        lo.put("count", l.count);
                        la.put(lo);
                    }
                }
                waitsMap.put(String.valueOf(sid), la);
            }
            root.put("scannedWaits", waitsMap);

            JSONArray arr = new JSONArray();
            for (CellDiff d : res.diffs) {
                JSONObject dj = new JSONObject();
                dj.put("cellId", d.cellId);
                dj.put("selected", d.selected);
                dj.put("occludedHint", d.occludedHint);
                dj.put("occludedByCellId", d.occludedByCellId);
                dj.put("note", d.note != null ? d.note : "");
                dj.put("appPlain", formatLayersPlain(d.appLayers));
                dj.put("gamePlain", formatLayersPlain(d.gameLayers));

                JSONArray appA = new JSONArray();
                for (Layer l : d.appLayers) {
                    JSONObject lo = new JSONObject();
                    lo.put("colour", l.colour);
                    lo.put("count", l.count);
                    appA.put(lo);
                }
                dj.put("appLayers", appA);

                JSONArray gameA = new JSONArray();
                for (Layer l : d.gameLayers) {
                    JSONObject lo = new JSONObject();
                    lo.put("colour", l.colour);
                    lo.put("count", l.count);
                    gameA.put(lo);
                }
                dj.put("gameLayers", gameA);
                arr.put(dj);
            }
            root.put("diffs", arr);
        } catch (Exception ignored) {}
        return root;
    }

    public static String formatLayersPlain(List<Layer> layers) {
        if (layers == null || layers.isEmpty()) return "пусто";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < layers.size(); i++) {
            if (i > 0) sb.append("→");
            Layer l = layers.get(i);
            String nm = (l.colour >= 1 && l.colour <= 8) ? SHORT_NAMES_RU[l.colour] : ("Ц" + l.colour);
            sb.append(nm).append("×").append(l.count);
        }
        return sb.toString();
    }

    /**
     * Normalized full-screen geometry mapping hex cells and hand valley to real Android display pixels.
     */
    public static final class ScreenBoardGeometry {
        public int stageNo = 7;
        public int rotationDeg = 0;
        public double paneLeftNorm = 0.0;
        public double paneWidthNorm = 1.0;
        public double gameTopNormY = 0.02;
        public double centerXNorm = 0.50;
        public double centerYNorm = 0.431;
        public double scaleXNorm = 0.1368;
        public double scaleYNorm = 0.0806;
        public double boardTopNormY = 0.215;
        public double boardBotNormY = 0.625;
        public double handTopNormY = 0.718;
        public double midWorldX = -43.0;
        public double midWorldY = -75.0;
        public boolean calibratedFromScreen = false;

        public float[] getCellScreenCenter(int cellId, int realScreenW, int realScreenH) {
            double[] wp = cellWorldPos(cellId, rotationDeg);
            float cx = (float) ((centerXNorm + ((wp[0] - midWorldX) / 100.0) * scaleXNorm) * realScreenW);
            float cy = (float) ((centerYNorm + ((wp[1] - midWorldY) / 100.0) * scaleYNorm) * realScreenH);
            float hexRx = (float) (0.525 * scaleXNorm * realScreenW);
            return new float[]{cx, cy, hexRx};
        }
    }

    public static ScreenBoardGeometry createDefaultScreenGeometry(StageCfg stg, int rotationDeg, int realW, int realH) {
        ScreenBoardGeometry g = new ScreenBoardGeometry();
        if (stg != null) g.stageNo = stg.stageNo;
        g.rotationDeg = rotationDeg;
        double minX = 1e9, maxX = -1e9, minY = 1e9, maxY = -1e9;
        if (stg != null && stg.cells != null && !stg.cells.isEmpty()) {
            for (StageCellCfg sc : stg.cells) {
                double[] wp = cellWorldPos(sc.cellId, rotationDeg);
                if (wp[0] < minX) minX = wp[0];
                if (wp[0] > maxX) maxX = wp[0];
                if (wp[1] < minY) minY = wp[1];
                if (wp[1] > maxY) maxY = wp[1];
            }
            g.midWorldX = 0.5 * (minX + maxX);
            g.midWorldY = 0.5 * (minY + maxY);
        }
        double aspect = (realH > 0 && realW > 0) ? ((double) realW / realH) : 0.56;
        g.centerXNorm = 0.50;
        g.centerYNorm = 0.431;
        g.scaleXNorm = 0.1368;
        g.scaleYNorm = g.scaleXNorm * aspect * 1.012;
        if (minY < maxY) {
            g.boardTopNormY = g.centerYNorm + ((minY - g.midWorldY) / 100.0) * g.scaleYNorm - 0.50 * g.scaleXNorm * aspect;
            g.boardBotNormY = g.centerYNorm + ((maxY - g.midWorldY) / 100.0) * g.scaleYNorm + 0.46 * g.scaleXNorm * aspect;
        }
        g.handTopNormY = Math.min(0.738, Math.max(g.boardBotNormY + 0.045, 0.718));
        g.calibratedFromScreen = false;
        return g;
    }

    public static ScreenBoardGeometry detectScreenBoardGeometry(
            Bitmap rawBmp, int fallbackStageNo, int rotationDeg,
            Calibration userCalib, Map<Integer, int[]> customPalette, Map<Integer, StageCfg> allStages
    ) {
        return detectScreenBoardGeometry(
                rawBmp, fallbackStageNo, 0, rotationDeg, userCalib, customPalette, allStages, false, null
        );
    }

    /**
     * Fast (~14 ms) screen camera + 6-angle rotation lock from raw MediaProjection Bitmap so AR target circles 1-2-3
     * and the bottom 3-stack strip land on exact sub-pixel hex centers on any phone screen and at any board angle.
     * Note (Task #5): Stage detection runs strictly ONLY when allowStageDetect == true ("✋ Рука").
     */
    public static ScreenBoardGeometry detectScreenBoardGeometry(
            Bitmap rawBmp, int fallbackStageNo, int curScore, int prevRotationDeg,
            Calibration userCalib, Map<Integer, int[]> customPalette, Map<Integer, StageCfg> allStages,
            boolean allowStageDetect, CellState[] knownBoard
    ) {
        if (rawBmp == null || allStages == null || allStages.isEmpty()) {
            StageCfg stg = (allStages != null) ? allStages.get(fallbackStageNo) : null;
            return createDefaultScreenGeometry(stg, prevRotationDeg, 1080, 2400);
        }
        int rawW = rawBmp.getWidth();
        int rawH = rawBmp.getHeight();
        int paneX = 0;
        Bitmap paneBmp = rawBmp;
        if (userCalib == null) {
            if (rawW > rawH * 1.02) {
                paneBmp = cropSplitScreenGamePane(rawBmp, customPalette, allStages);
                paneX = Math.max(0, rawW - paneBmp.getWidth());
            } else {
                paneBmp = trimEmulatorChromeBars(rawBmp);
                // On portrait emulator screenshots (e.g. BlueStacks right toolbar), left edge is 0 unless left pillarbox exists
                paneX = 0;
            }
        }
        int paneW = paneBmp.getWidth();
        int paneH = paneBmp.getHeight();

        int workW = Math.min(360, paneW);
        int workH = Math.max(180, Math.round((float) paneH * workW / Math.max(1, paneW)));
        Bitmap workBmp = (workW == paneW && workH == paneH) ? paneBmp : Bitmap.createScaledBitmap(paneBmp, workW, workH, true);
        int[] px = new int[workW * workH];
        workBmp.getPixels(px, 0, workW, 0, 0, workW, workH);
        if (workBmp != paneBmp) workBmp.recycle();
        if (paneBmp != rawBmp) paneBmp.recycle();

        int nPix = workW * workH;
        int[] hsvH = new int[nPix];
        int[] hsvS = new int[nPix];
        int[] hsvV = new int[nPix];
        for (int i = 0; i < nPix; i++) {
            int c = px[i];
            int[] hsv = rgbToHsvOpenCv((c >> 16) & 0xFF, (c >> 8) & 0xFF, c & 0xFF);
            hsvH[i] = hsv[0]; hsvS[i] = hsv[1]; hsvV[i] = hsv[2];
        }

        int gameTopY = 0;
        for (int y = 0; y < (int) (0.20 * workH); y++) {
            int darkRow = 0;
            for (int x = (int) (0.15 * workW); x < (int) (0.85 * workW); x += 4) {
                if (hsvV[y * workW + x] < 52) darkRow++;
            }
            int tot = Math.max(1, ((int) (0.85 * workW) - (int) (0.15 * workW) + 3) / 4);
            if ((double) darkRow / tot >= 0.85) gameTopY = y + 1;
            else break;
        }
        int gameBotY = workH;
        for (int y = workH - 1; y >= (int) (0.78 * workH); y--) {
            int darkRow = 0;
            for (int x = (int) (0.15 * workW); x < (int) (0.85 * workW); x += 4) {
                if (hsvV[y * workW + x] < 52) darkRow++;
            }
            int tot = Math.max(1, ((int) (0.85 * workW) - (int) (0.15 * workW) + 3) / 4);
            if ((double) darkRow / tot >= 0.85) gameBotY = y;
            else break;
        }
        int gameH = Math.max(180, gameBotY - gameTopY);

        boolean[] themeAndDanger = detectThemeAndRedVignette(px, hsvH, hsvV, workW, workH, gameTopY, gameBotY);
        boolean isWarmTheme = themeAndDanger[0];
        boolean redDangerActive = themeAndDanger[1];

        byte[] chipMask = new byte[nPix];
        byte[] emptyMask = new byte[nPix];
        byte[] lockedMask = new byte[nPix];
        for (int y = 0; y < workH; y++) {
            int ro = y * workW;
            for (int x = 0; x < workW; x++) {
                int i = ro + x;
                int hc = hsvH[i], sc = hsvS[i], vc = hsvV[i];
                int rc = (px[i] >> 16) & 0xFF, gc = (px[i] >> 8) & 0xFF, bc = px[i] & 0xFF;
                if (redDangerActive && isRedVignetteBorderPixel(x, y, workW, gameTopY, gameBotY, rc, gc, bc, hc)) {
                    continue;
                }
                if (isWarmTheme) {
                    if (((hc <= 8 || hc >= 32) && sc >= 98 && vc >= 86) ||
                        (hc > 8 && hc < 32 && sc >= 174 && vc >= 115 && (bc <= 74 || (rc - bc) >= 152))) {
                        chipMask[i] = 1;
                    } else if (hc >= 15 && hc <= 26 && sc >= 70 && sc <= 116 && vc >= 155 && vc <= 208) {
                        emptyMask[i] = 1;
                    } else if (hc >= 11 && hc <= 25 && sc >= 78 && sc <= 168 && vc >= 74 && vc <= 138) {
                        lockedMask[i] = 1;
                    }
                } else {
                    if (sc >= 96 && vc >= 115) chipMask[i] = 1;
                    else if (sc < 85 && vc >= 68 && vc <= 130) emptyMask[i] = 1;
                    else if (sc < 85 && vc >= 42 && vc < 68) lockedMask[i] = 1;
                }
            }
        }

        int effStageNo = fallbackStageNo;
        int effScore = curScore;
        if (allowStageDetect) {
            int[] hdr = detectHeaderStageAndScore(px, workW, workH, hsvS, hsvV, allStages);
            if (hdr[0] >= 1 && allStages.containsKey(hdr[0])) effStageNo = hdr[0];
            if (hdr[1] >= 0) effScore = hdr[1];
        }
        StageCfg stg = allStages.containsKey(effStageNo) ? allStages.get(effStageNo) : allStages.get(1);

        // Automatic board orientation detection disabled by user instruction — strictly keep current manual rotationDeg
        int rotationDeg = (((prevRotationDeg % 360) + 360) % 360);

        double minX = 1e9, maxX = -1e9, minY = 1e9, maxY = -1e9;
        Map<Integer, double[]> rawMap = new HashMap<>();
        for (StageCellCfg scItem : stg.cells) {
            double[] wp = cellWorldPos(scItem.cellId, rotationDeg);
            rawMap.put(scItem.cellId, wp);
            if (wp[0] < minX) minX = wp[0];
            if (wp[0] > maxX) maxX = wp[0];
            if (wp[1] < minY) minY = wp[1];
            if (wp[1] > maxY) maxY = wp[1];
        }
        double midX = 0.5 * (minX + maxX), midY = 0.5 * (minY + maxY);
        Map<Integer, double[]> centered = new HashMap<>();
        List<double[]> activePts = new ArrayList<>();
        for (StageCellCfg scItem : stg.cells) {
            double[] wp = rawMap.get(scItem.cellId);
            double[] cp = new double[]{wp[0] - midX, wp[1] - midY};
            centered.put(scItem.cellId, cp);
            activePts.add(cp);
        }

        double cx0, cy0, sc, sy;
        if (userCalib != null) {
            cx0 = userCalib.centerX * workW;
            cy0 = userCalib.centerY * workH;
            sc = userCalib.scaleX * workW;
            sy = userCalib.scaleY * workH;
        } else {
            double[] fit = fitBoardCamera(px, activePts, minX - midX, maxX - midX, maxY - midY, chipMask, emptyMask, lockedMask, workW, workH);
            cx0 = fit[0]; cy0 = fit[1]; sc = fit[2]; sy = fit[3];

            // Sub-pixel vertical skirt refinement
            double flatHw = 0.270 * sc;
            double floorDy = 0.432 * sc;
            int winSearch = Math.max(5, (int) Math.round(0.11 * sc));
            List<Double> cy0Ests = new ArrayList<>();
            for (StageCellCfg scItem : stg.cells) {
                double[] cp = centered.get(scItem.cellId);
                double bx = cx0 + (cp[0] / 100.0) * sc;
                double byNom = cy0 + (cp[1] / 100.0) * sy;
                int fxL = Math.max(0, (int) Math.round(bx - flatHw * 0.62));
                int fxR = Math.min(workW, (int) Math.round(bx + flatHw * 0.62));
                if (fxR - fxL < 4) continue;
                int bodyY0 = Math.max(0, (int) Math.round(byNom - 0.30 * sc));
                int bodyY1 = Math.min(workH, (int) Math.round(byNom + 0.15 * sc));
                int bodyTot = 0, bodyChp = 0;
                for (int y = bodyY0; y < bodyY1; y++) {
                    for (int x = fxL; x < fxR; x++) {
                        bodyTot++;
                        if (chipMask[y * workW + x] != 0) bodyChp++;
                    }
                }
                if (bodyTot == 0 || (double) bodyChp / bodyTot < 0.38) continue;
                int nomBotY = (int) Math.round(byNom + floorDy);
                int sy0 = Math.max(2, nomBotY - winSearch);
                int sy1 = Math.min(workH - 5, nomBotY + winSearch);
                for (int y = sy1; y >= sy0; y--) {
                    int cOn = 0;
                    for (int x = fxL; x < fxR; x++) if (chipMask[y * workW + x] != 0) cOn++;
                    if ((double) cOn / (fxR - fxL) >= 0.50) {
                        boolean cleanBelow = true;
                        for (int dy = 1; dy <= 3; dy++) {
                            int cBel = 0;
                            for (int x = fxL; x < fxR; x++) if (chipMask[(y + dy) * workW + x] != 0) cBel++;
                            if ((double) cBel / (fxR - fxL) > 0.12) { cleanBelow = false; break; }
                        }
                        if (cleanBelow) cy0Ests.add((y + 0.5) - floorDy - (cp[1] / 100.0) * sy);
                        break;
                    }
                }
            }
            if (!cy0Ests.isEmpty()) cy0 = medianDouble(cy0Ests);
        }

        double floorNormY = (0.455 * sc) / Math.max(1, workH);
        double topCapNormY = (0.50 * sc) / Math.max(1, workH);
        double boardTopNormY = (cy0 / Math.max(1, workH)) + ((minY - midY) / 100.0) * (sy / Math.max(1, workH)) - topCapNormY;
        double boardBotNormY = (cy0 / Math.max(1, workH)) + ((maxY - midY) / 100.0) * (sy / Math.max(1, workH)) + floorNormY;

        // Task #3: Detect top edge of the 3 hand stacks strictly inside the hand zone [max(boardBot+6px, 0.695*gameH) .. 0.805*gameH]
        // across the 3 physical slot columns (0.24W, 0.50W, 0.76W) so partial/empty hands or tall 5-row boards
        // NEVER push handTopNormY down onto the booster buttons (~0.88H)!
        int boardBotPxWork = (int) Math.round(boardBotNormY * workH);
        int handScanY0 = Math.max(boardBotPxWork + 4, gameTopY + (int) (0.692 * gameH));
        int handScanY1 = Math.min(gameBotY - 12, gameTopY + (int) (0.805 * gameH));
        int defaultHandY = gameTopY + (int) (0.722 * gameH);
        int detectedHandY = -1;
        double[] slotCenters = {0.245, 0.500, 0.755};
        int slotHalfW = Math.max(5, (int) (0.065 * workW));
        for (int y = handScanY0; y < handScanY1; y++) {
            boolean hitAnySlot = false;
            for (double scx : slotCenters) {
                int sxA = Math.max(0, (int) Math.round(scx * workW) - slotHalfW);
                int sxB = Math.min(workW, (int) Math.round(scx * workW) + slotHalfW);
                int cHits = 0;
                for (int x = sxA; x < sxB; x++) {
                    if (chipMask[y * workW + x] != 0) cHits++;
                }
                if (cHits >= Math.max(3, (sxB - sxA) / 4)) {
                    hitAnySlot = true;
                    break;
                }
            }
            if (hitAnySlot) {
                detectedHandY = y;
                break;
            }
        }
        int handY0 = (detectedHandY > 0) ? detectedHandY : defaultHandY;
        double rawHandNorm = (double) handY0 / Math.max(1, workH);
        double minSafeHandNorm = Math.max(boardBotNormY + 0.025, (gameTopY + 0.705 * gameH) / Math.max(1, workH));
        double maxSafeHandNorm = (gameTopY + 0.752 * gameH) / Math.max(1, workH);
        double clampedHandNorm = Math.max(minSafeHandNorm, Math.min(maxSafeHandNorm, rawHandNorm));

        ScreenBoardGeometry geo = new ScreenBoardGeometry();
        geo.stageNo = stg.stageNo;
        geo.rotationDeg = rotationDeg;
        geo.paneLeftNorm = (double) paneX / Math.max(1, rawW);
        geo.paneWidthNorm = (double) paneW / Math.max(1, rawW);
        geo.gameTopNormY = (double) gameTopY / Math.max(1, workH);
        geo.centerXNorm = ((double) paneX + (cx0 / workW) * paneW) / Math.max(1, rawW);
        geo.centerYNorm = cy0 / Math.max(1, workH);
        geo.scaleXNorm = ((sc / workW) * paneW) / Math.max(1, rawW);
        geo.scaleYNorm = sy / Math.max(1, workH);
        geo.midWorldX = midX;
        geo.midWorldY = midY;
        geo.boardTopNormY = boardTopNormY;
        geo.boardBotNormY = boardBotNormY;
        geo.handTopNormY = clampedHandNorm;
        geo.calibratedFromScreen = true;
        return geo;
    }
}
