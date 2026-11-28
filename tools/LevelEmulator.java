package ru.doublejoy.solver;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Эмулятор уровней (headless): проходит этапы НА МОДЕЛИ игры — без телефона.
 *
 * Источники рук:
 *  1) цепочка реальных переходов из HandTransitionsData (обучена на логах игроков);
 *  2) при промахе цепочки — синтетические распределения движка (ROLLOUT suites).
 *
 * Политика (как в игре):
 *  - ход: лучший план решалки, применяется по шагам через simulatePlaceCard;
 *  - если план частичный (partialBeforeReroll) — применяется и кидается 🔄;
 *  - рероллы ограничены (бюджет на этап);
 *  - без бустеров (🔨 не моделируется) — честный нижний путь.
 *
 * Запуск:
 *   javac -cp android.jar -sourcepath src -d /tmp/emu tools/LevelEmulator.java
 *   java -cp /tmp/emu:android.jar ru.doublejoy.solver.LevelEmulator \
 *        double_joy_android_apk/assets/stages_config.json balanced 12 16
 */
public class LevelEmulator {

    // ---------- руки: синтетические сьюты движка (через reflection — они private) ----------
    @SuppressWarnings("unchecked")
    private static List<List<List<SolverEngine.Layer>>> suite(int bracket) throws Exception {
        String name = bracket == 0 ? "ROLLOUT_EARLY" : (bracket == 1 ? "ROLLOUT_MID" : "ROLLOUT_LATE");
        java.lang.reflect.Field f = SolverEngine.class.getDeclaredField(name);
        f.setAccessible(true);
        return (List<List<List<SolverEngine.Layer>>>) f.get(null);
    }

    // ---------- руки: цепочка из обученной таблицы ----------
    static Map<String, String> stageTable = new HashMap<>();
    static Map<String, String> globalTable = new HashMap<>();

    static void loadTransitions() {
        for (String l : HandTransitionsData.STAGE_TABLE.split("\n")) {
            if (l.isEmpty()) continue;
            String[] p = l.split(";", 3);
            if (p.length == 3) stageTable.put(p[0] + "|" + p[1], p[2]);
        }
        for (String l : HandTransitionsData.GLOBAL_TABLE.split("\n")) {
            if (l.isEmpty()) continue;
            String[] p = l.split(";", 2);
            if (p.length == 2) globalTable.put(p[0], p[1]);
        }
    }

    static String slotSig(List<SolverEngine.Layer> layers) {
        if (layers == null || layers.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        for (SolverEngine.Layer l : layers) sb.append(l.colour).append('_').append(l.count).append('|');
        return sb.toString();
    }

    static String handKey(List<List<SolverEngine.Layer>> hand) {
        List<String> sigs = new ArrayList<>();
        for (List<SolverEngine.Layer> sl : hand) {
            String sg = slotSig(sl);
            if (sg.isEmpty()) return null;
            sigs.add(sg);
        }
        java.util.Collections.sort(sigs);
        return String.join(",", sigs);
    }

    static List<List<SolverEngine.Layer>> parseHand(String key) {
        List<List<SolverEngine.Layer>> hand = new ArrayList<>(3);
        for (String slot : key.split(",")) {
            List<SolverEngine.Layer> layers = new ArrayList<>();
            for (String pr : slot.split("\\|")) {
                if (pr.isEmpty()) continue;
                int us = pr.indexOf('_');
                if (us <= 0) continue;
                try {
                    layers.add(new SolverEngine.Layer(
                            Integer.parseInt(pr.substring(0, us)),
                            Integer.parseInt(pr.substring(us + 1))));
                } catch (NumberFormatException ignore) {}
            }
            if (!layers.isEmpty()) hand.add(layers);
        }
        return hand.size() == 3 ? hand : null;
    }

    static List<List<SolverEngine.Layer>> nextHand(int stageNo, List<List<SolverEngine.Layer>> cur, int round) throws Exception {
        String key = handKey(cur);
        if (key != null) {
            String nxt = stageTable.get(stageNo + "|" + key);
            if (nxt == null) nxt = globalTable.get(key);
            if (nxt != null) {
                List<List<SolverEngine.Layer>> h = parseHand(nxt);
                if (h != null) return h;
            }
        }
        // fallback: синтетика по прогрессу счёта (как в прокатке движка)
        return synthetic(round, cur == null ? 0 : -1);
    }

    static int synthBracket = 0; // выставляется извне по прогрессу

    static List<List<SolverEngine.Layer>> synthetic(int round, int unused) throws Exception {
        List<List<List<SolverEngine.Layer>>> s = suite(synthBracket);
        return s.get(round % s.size());
    }

    // ---------- загрузка конфига этапов ----------
    static Map<Integer, SolverEngine.StageCfg> loadStages(String path) throws Exception {
        String txt = new String(Files.readAllBytes(Paths.get(path)), StandardCharsets.UTF_8);
        JSONObject root = new JSONObject(txt);
        JSONArray arr = root.getJSONArray("stages");
        Map<Integer, SolverEngine.StageCfg> out = new HashMap<>();
        for (int i = 0; i < arr.length(); i++) {
            JSONObject sj = arr.getJSONObject(i);
            SolverEngine.StageCfg sc = new SolverEngine.StageCfg();
            sc.stageNo = sj.getInt("stageNo");
            sc.targetScore = sj.getInt("targetScore");
            sc.boardType = sj.optInt("boardType", 1);
            JSONArray cj = sj.getJSONArray("cells");
            for (int k = 0; k < cj.length(); k++) {
                JSONObject c = cj.getJSONObject(k);
                SolverEngine.StageCellCfg scc = new SolverEngine.StageCellCfg();
                scc.cellId = c.getInt("cellId");
                scc.unlockCellScore = c.optInt("unlockCellScore", 0);
                scc.unlockCardScore = c.optInt("unlockCardScore", 0);
                JSONArray initArr = c.optJSONArray("initLayers");
                if (initArr != null) {
                    for (int m = 0; m < initArr.length(); m++) {
                        JSONObject lj = initArr.getJSONObject(m);
                        scc.initLayers.add(new SolverEngine.Layer(lj.getInt("colour"), lj.getInt("count")));
                    }
                }
                sc.cells.add(scc);
            }
            out.put(sc.stageNo, sc);
        }
        return out;
    }

    static Map<Integer, List<SolverEngine.Layer>> toWaits(List<List<SolverEngine.Layer>> hand) {
        Map<Integer, List<SolverEngine.Layer>> waits = new HashMap<>();
        for (int i = 0; i < hand.size() && i < 3; i++) waits.put(i + 1, hand.get(i));
        return waits;
    }

    static int emptyCount(SolverEngine.CellState[] board) {
        int n = 0;
        for (int cid = 1; cid <= SolverEngine.MAX_POS_COUNT; cid++) {
            SolverEngine.CellState c = board[cid];
            if (c != null && c.exist == 1 && c.isEmptyPlaceable()) n++;
        }
        return n;
    }

    // ---------- прохождение одного этапа ----------
    static class StageResult {
        String status; int score; int hands; int rerolls; int hammers; int steps; String note = "";
    }

    /** Лучшая клетка для удара молотком: совет движка, иначе — самая высокая стопка. */
    static int pickHammerTarget(SolverEngine.CellState[] board, int score, SolverEngine.SolvePlan plan) {
        if (plan != null && plan.hammerCellId > 0) return plan.hammerCellId;
        int best = -1, bestLayers = -1;
        for (int cid = 1; cid <= SolverEngine.MAX_POS_COUNT; cid++) {
            SolverEngine.CellState c = board[cid];
            if (c == null || c.exist != 1 || c.layers.isEmpty()) continue;
            if (c.lockScore > score || c.lockCardScore > score) continue; // замок ещё не открыт
            if (c.layers.size() > bestLayers) { bestLayers = c.layers.size(); best = cid; }
        }
        return best;
    }

    static StageResult playStage(SolverEngine.StageCfg stg, String mode, int rerollBudget, int hammerBudget, int maxHands) throws Exception {
        StageResult res = new StageResult();
        SolverEngine.CellState[] board = SolverEngine.createStageBoard(stg, 0);
        int score = 0;
        int round = 0;
        List<List<SolverEngine.Layer>> hand = synthetic(0, -1);
        int rerolls = 0, hammers = 0, stepsDone = 0;

        while (res.hands < maxHands) {
            res.hands++;
            double prog = stg.targetScore > 0 ? (double) score / stg.targetScore : 0;
            synthBracket = prog < 0.35 ? 0 : (prog < 0.78 ? 1 : 2);

            List<SolverEngine.SolvePlan> plans = SolverEngine.solveTopPlans(
                    board, toWaits(hand), score, stg.targetScore, mode, 3, stg.stageNo, false);
            SolverEngine.SolvePlan plan = (plans != null && !plans.isEmpty()) ? plans.get(0) : null;

            if (plan == null || plan.steps == null || plan.steps.isEmpty()) {
                // 1) МОЛОТОК: расчистить место и продолжить с той же рукой
                int ht = pickHammerTarget(board, score, plan);
                if (hammers < hammerBudget && ht > 0) {
                    hammers++;
                    board[ht].layers.clear();
                    board[ht].normalize();
                    continue;
                }
                // 2) РЕРОЛЛ по бюджету
                if (rerolls < rerollBudget && (plan == null || plan.deadlockStopAll || plan.recommendReroll)) {
                    rerolls++;
                    hand = nextHand(stg.stageNo, hand, ++round);
                    continue;
                }
                res.status = "DEAD";
                res.score = score;
                res.rerolls = rerolls;
                res.hammers = hammers;
                res.steps = stepsDone;
                res.note = (plan != null && plan.deadlockStopAll) ? "безвыходный тупик" : "нет валидных ходов";
                return res;
            }

            // применяем шаги плана
            boolean simOk = true;
            for (SolverEngine.StepMove sm : plan.steps) {
                SolverEngine.SimResult smr = SolverEngine.simulatePlaceCard(board, sm.cardLayers, sm.targetCellId, score);
                if (!smr.valid) { simOk = false; break; }
                board = smr.newCells;
                score = smr.finalScore;
                stepsDone++;
            }
            if (!simOk) {
                res.status = "ERROR"; res.score = score; res.note = "шаг плана не применился";
                res.rerolls = rerolls; res.hammers = hammers; res.steps = stepsDone;
                return res;
            }

            if (score >= stg.targetScore || plan.isWin) {
                res.status = "PASS";
                res.score = score; res.rerolls = rerolls; res.hammers = hammers; res.steps = stepsDone;
                return res;
            }

            // дальше: частичный план → реролл; иначе следующая рука (таблица/синтетика)
            if (plan.partialBeforeReroll) {
                if (rerolls >= rerollBudget) {
                    int ht = pickHammerTarget(board, score, plan);
                    if (hammers < hammerBudget && ht > 0) {
                        hammers++;
                        board[ht].layers.clear();
                        board[ht].normalize();
                        continue;
                    }
                    res.status = "DEAD"; res.score = score; res.rerolls = rerolls; res.hammers = hammers; res.steps = stepsDone;
                    res.note = "частичный план, рероллы и молотки кончились";
                    return res;
                }
                rerolls++;
            }
            hand = nextHand(stg.stageNo, hand, ++round);
        }

        res.status = "TIMEOUT"; res.score = score; res.rerolls = rerolls; res.hammers = hammers; res.steps = stepsDone;
        res.note = "исчерпан лимит рук";
        return res;
    }

    public static void main(String[] args) throws Exception {
        String cfg = args.length > 0 ? args[0] : "double_joy_android_apk/assets/stages_config.json";
        String mode = args.length > 1 ? args[1] : "balanced";
        int from = args.length > 2 ? Integer.parseInt(args[2]) : 12;
        int to = args.length > 3 ? Integer.parseInt(args[3]) : 16;
        int rerollBudget = args.length > 4 ? Integer.parseInt(args[4]) : 8;
        int hammerBudget = args.length > 5 ? Integer.parseInt(args[5]) : 8;

        // включаем расчёт совета молотка (для эмулятора), панель бустеров «не распознана» → не блокируем
        AppState.get().useHintsInCalc = true;
        AppState.get().boostHammer = -1;

        loadTransitions();
        Map<Integer, SolverEngine.StageCfg> stages = loadStages(cfg);
        System.out.println("Эмулятор этапов " + from + ".." + to + " | режим=" + mode
                + " | рероллов на этап: " + rerollBudget + " | молотков: " + hammerBudget
                + " | переходов в таблице: " + stageTable.size() + "+"
                + globalTable.size());
        System.out.println();

        int passed = 0, total = 0;
        for (int sno = from; sno <= to; sno++) {
            SolverEngine.StageCfg stg = stages.get(sno);
            if (stg == null) {
                System.out.printf("Этап %2d: НЕТ В КОНФИГЕ%n", sno);
                continue;
            }
            total++;
            long t0 = System.currentTimeMillis();
            StageResult r = playStage(stg, mode, rerollBudget, hammerBudget, 150);
            long ms = System.currentTimeMillis() - t0;
            if ("PASS".equals(r.status)) passed++;
            System.out.printf("Этап %2d: %-7s счёт %3d/%d | рук %d (реролл %d, молоток %d, ходов %d) | %.1f с | %s%n",
                    sno, r.status, r.score, stg.targetScore, r.hands, r.rerolls, r.hammers, r.steps, ms / 1000.0,
                    r.note.isEmpty() ? "" : ("[" + r.note + "]"));
        }
        System.out.println();
        System.out.println("ИТОГО: " + passed + "/" + total + " этапов пройдено");
        System.exit(passed == total ? 0 : 1);
    }
}
