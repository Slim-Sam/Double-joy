package ru.doublejoy.solver;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.view.MotionEvent;
import android.view.View;

import java.util.HashMap;
import java.util.Map;

/**
 * Interactive hexagonal board diagram rendered inside the collapsible "Поле ▲" drawer
 * of the floating overlay window. Supports tapping any hex cell to inspect all its hidden layers,
 * highlights target cells for Moves 1..3, and highlights pending diff cells in bright amber.
 */
public final class MiniHexOverlayView extends View {

    public interface OnCellTapListener {
        void onCellTapped(int cellId);
    }

    private final Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint strokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path hexPath = new Path();

    private final Map<Integer, float[]> lastCenters = new HashMap<>();
    private float lastHitRadius = 18f;
    private OnCellTapListener cellTapListener;

    public MiniHexOverlayView(Context context) {
        super(context);
        fillPaint.setStyle(Paint.Style.FILL);
        strokePaint.setStyle(Paint.Style.STROKE);
        textPaint.setTextAlign(Paint.Align.CENTER);
        textPaint.setFakeBoldText(true);
    }

    public void setOnCellTapListener(OnCellTapListener listener) {
        this.cellTapListener = listener;
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
            float tx = event.getX();
            float ty = event.getY();
            int bestCid = -1;
            float bestD2 = lastHitRadius * lastHitRadius * 1.45f;
            for (Map.Entry<Integer, float[]> e : lastCenters.entrySet()) {
                float dx = tx - e.getValue()[0];
                float dy = ty - e.getValue()[1];
                float d2 = dx * dx + dy * dy;
                if (d2 < bestD2) {
                    bestD2 = d2;
                    bestCid = e.getKey();
                }
            }
            if (bestCid > 0) {
                AppState.get().inspectedOverlayCellId = bestCid;
                invalidate();
                if (cellTapListener != null) {
                    cellTapListener.onCellTapped(bestCid);
                }
                return true;
            }
        }
        return super.onTouchEvent(event);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        AppState st = AppState.get();
        SolverEngine.CellState[] cells = st.cells;
        if (cells == null) return;

        int vw = getWidth();
        int vh = getHeight();
        if (vw <= 4 || vh <= 4) return;

        double minX = 1e9, maxX = -1e9, minY = 1e9, maxY = -1e9;
        Map<Integer, double[]> posMap = new HashMap<>();
        for (int cid = 1; cid <= SolverEngine.MAX_POS_COUNT; cid++) {
            if (cells[cid] != null && cells[cid].exist == 1) {
                double[] wp = SolverEngine.cellWorldPos(cid, st.rotationDeg);
                posMap.put(cid, wp);
                if (wp[0] < minX) minX = wp[0];
                if (wp[0] > maxX) maxX = wp[0];
                if (wp[1] < minY) minY = wp[1];
                if (wp[1] > maxY) maxY = wp[1];
            }
        }
        if (posMap.isEmpty()) return;

        Map<Integer, String> targetStep = new HashMap<>();
        Map<Integer, Boolean> diffCells = new HashMap<>();
        Map<Integer, Integer> occludedCells = new HashMap<>();
        if (st.pendingDiffResult != null) {
            if (st.pendingDiffResult.diffs != null) {
                for (SolverEngine.CellDiff d : st.pendingDiffResult.diffs) {
                    diffCells.put(d.cellId, d.selected);
                }
            }
            if (st.pendingDiffResult.occludedCellBlockers != null) {
                occludedCells.putAll(st.pendingDiffResult.occludedCellBlockers);
            }
        }
        boolean isDeadlockPlan = st.lastPlan != null && st.lastPlan.recommendReroll && st.lastPlan.finalEmptyCells == 0;
        if (st.lastPlan != null && st.lastPlan.steps != null) {
            for (SolverEngine.StepMove sm : st.lastPlan.steps) {
                String prev = targetStep.get(sm.targetCellId);
                if (prev == null) {
                    targetStep.put(sm.targetCellId, String.valueOf(sm.stepNum));
                } else {
                    targetStep.put(sm.targetCellId, prev + "·" + sm.stepNum);
                }
            }
        }

        double worldW = Math.max(220.0, (maxX - minX) + 102.0);
        double worldH = Math.max(235.0, (maxY - minY) + 110.0);
        double scale = Math.min((vw - 10.0) / worldW, (vh - 10.0) / worldH);
        double midX = 0.5 * (minX + maxX);
        double midY = 0.5 * (minY + maxY);
        float rx = (float) Math.max(5.0, 44.5 * scale);
        float ry = (float) Math.max(4.8, 43.0 * scale);
        lastHitRadius = rx;
        lastCenters.clear();

        int inspectedCid = st.inspectedOverlayCellId;

        for (Map.Entry<Integer, double[]> e : posMap.entrySet()) {
            int cid = e.getKey();
            double[] wp = e.getValue();
            float cx = (float) (vw * 0.5 + (wp[0] - midX) * scale);
            float cy = (float) (vh * 0.5 + (wp[1] - midY) * scale);
            lastCenters.put(cid, new float[]{cx, cy});

            SolverEngine.CellState cs = cells[cid];
            String moveLabel = targetStep.get(cid);
            Boolean diffSel = diffCells.get(cid);
            boolean isOccluded = occludedCells.containsKey(cid);
            boolean isInspected = (cid == inspectedCid);

            hexPath.reset();
            for (int k = 0; k < 6; k++) {
                double a = Math.toRadians(k * 60.0);
                float hx = cx + (float) (rx * Math.cos(a));
                float hy = cy + (float) (ry * Math.sin(a));
                if (k == 0) hexPath.moveTo(hx, hy);
                else hexPath.lineTo(hx, hy);
            }
            hexPath.close();

            if (diffSel != null) {
                fillPaint.setColor(diffSel ? 0xFFD97706 : 0xFF475569);
                strokePaint.setColor(diffSel ? 0xFFFDE047 : 0xFFCBD5E1);
                strokePaint.setStrokeWidth(Math.max(2.2f, rx * 0.22f));
            } else if (isOccluded) {
                fillPaint.setColor(cs.hasBlocks() ? SolverEngine.CHIP_HEX_ARGB[cs.topColor()] : 0xFF0C4A6E);
                strokePaint.setColor(0xFF38BDF8);
                strokePaint.setStrokeWidth(Math.max(2.0f, rx * 0.20f));
            } else if (cs.hasBlocks() && moveLabel != null) {
                // Cell currently has a stack AND will be cleared by an earlier step, then targeted by Ш2/Ш3!
                // Keep the REAL stack chip color visible so the stack is never hidden!
                fillPaint.setColor(SolverEngine.CHIP_HEX_ARGB[cs.topColor()]);
                strokePaint.setColor(isDeadlockPlan ? 0xFFFCA5A5 : 0xFF34D399);
                strokePaint.setStrokeWidth(Math.max(2.5f, rx * 0.24f));
            } else if (cs.isLocked() && moveLabel != null) {
                fillPaint.setColor(0x8878350F);
                strokePaint.setColor(isDeadlockPlan ? 0xFFFCA5A5 : 0xFF34D399);
                strokePaint.setStrokeWidth(Math.max(2.3f, rx * 0.22f));
            } else if (moveLabel != null) {
                fillPaint.setColor(isDeadlockPlan ? 0xFFB91C1C : 0xFF059669);
                strokePaint.setColor(isDeadlockPlan ? 0xFFFCA5A5 : 0xFF6EE7B7);
                strokePaint.setStrokeWidth(Math.max(2.0f, rx * 0.20f));
            } else if (cs.isLocked()) {
                fillPaint.setColor(0x6678350F);
                strokePaint.setColor(0xFFF59E0B);
                strokePaint.setStrokeWidth(1.5f);
            } else if (cs.hasBlocks()) {
                int tc = cs.topColor();
                fillPaint.setColor(SolverEngine.CHIP_HEX_ARGB[tc]);
                strokePaint.setColor(0xDDFFFFFF);
                strokePaint.setStrokeWidth(1.3f);
            } else {
                fillPaint.setColor(0xFF1E293B);
                strokePaint.setColor(0xFF475569);
                strokePaint.setStrokeWidth(1.1f);
            }

            canvas.drawPath(hexPath, fillPaint);
            canvas.drawPath(hexPath, strokePaint);

            if (isInspected) {
                strokePaint.setColor(0xFF38BDF8);
                strokePaint.setStrokeWidth(Math.max(2.4f, rx * 0.24f));
                canvas.drawCircle(cx, cy, rx * 1.04f, strokePaint);
            }

            boolean richTwoLine = ry >= 11.5f;
            if (diffSel != null) {
                textPaint.setColor(0xFFFFFFFF);
                if (richTwoLine && cs.hasBlocks()) {
                    textPaint.setTextSize(Math.max(7.0f, ry * 0.48f));
                    canvas.drawText("!#" + cid, cx, cy - ry * 0.16f, textPaint);
                    textPaint.setColor(0xFFFEF08A);
                    canvas.drawText("×" + cs.topCount(), cx, cy + ry * 0.44f, textPaint);
                } else {
                    textPaint.setTextSize(Math.max(7.2f, ry * 0.68f));
                    canvas.drawText("!#" + cid, cx, cy + ry * 0.28f, textPaint);
                }
            } else if (cs.hasBlocks() && moveLabel != null) {
                textPaint.setColor(0xFF0F172A);
                if (richTwoLine) {
                    textPaint.setTextSize(Math.max(6.4f, ry * 0.41f));
                    canvas.drawText("#" + cid + "·×" + cs.topCount(), cx, cy - ry * 0.18f, textPaint);
                    fillPaint.setColor(0xEE064E3B);
                    canvas.drawRoundRect(cx - rx * 0.72f, cy + ry * 0.04f, cx + rx * 0.72f, cy + ry * 0.64f, 4f, 4f, fillPaint);
                    textPaint.setColor(0xFFA7F3D0);
                    textPaint.setTextSize(Math.max(6.5f, ry * 0.43f));
                    canvas.drawText("→Ш" + moveLabel, cx, cy + ry * 0.47f, textPaint);
                } else {
                    textPaint.setTextSize(Math.max(6.8f, ry * 0.60f));
                    canvas.drawText("×" + cs.topCount() + "→" + moveLabel, cx, cy + ry * 0.28f, textPaint);
                }
            } else if (cs.isLocked() && moveLabel != null) {
                textPaint.setColor(0xFFA7F3D0);
                if (richTwoLine) {
                    textPaint.setTextSize(Math.max(6.4f, ry * 0.41f));
                    canvas.drawText("🔓#" + cid, cx, cy - ry * 0.16f, textPaint);
                    canvas.drawText("→Ш" + moveLabel, cx, cy + ry * 0.44f, textPaint);
                } else {
                    textPaint.setTextSize(Math.max(6.8f, ry * 0.60f));
                    canvas.drawText("→Ш" + moveLabel, cx, cy + ry * 0.28f, textPaint);
                }
            } else if (moveLabel != null) {
                if (richTwoLine) {
                    textPaint.setColor(0xFFD1FAE5);
                    textPaint.setTextSize(Math.max(6.8f, ry * 0.44f));
                    canvas.drawText("Ш" + moveLabel, cx, cy - ry * 0.16f, textPaint);
                    textPaint.setColor(0xFFFFFFFF);
                    textPaint.setTextSize(Math.max(7.8f, ry * 0.54f));
                    canvas.drawText("#" + cid, cx, cy + ry * 0.44f, textPaint);
                } else {
                    textPaint.setColor(0xFFFFFFFF);
                    textPaint.setTextSize(Math.max(8.2f, ry * 0.85f));
                    canvas.drawText(moveLabel, cx, cy + ry * 0.34f, textPaint);
                }
            } else if (cs.isLocked()) {
                int lsc = Math.max(cs.lockScore, cs.lockCardScore);
                textPaint.setColor(0xFFFDE68A);
                if (richTwoLine && lsc > 0) {
                    textPaint.setTextSize(Math.max(6.5f, ry * 0.42f));
                    canvas.drawText("#" + cid, cx, cy - ry * 0.16f, textPaint);
                    textPaint.setTextSize(Math.max(6.8f, ry * 0.46f));
                    canvas.drawText(String.valueOf(lsc), cx, cy + ry * 0.44f, textPaint);
                } else {
                    textPaint.setTextSize(Math.max(7.0f, ry * 0.62f));
                    canvas.drawText("#" + cid, cx, cy + ry * 0.28f, textPaint);
                }
            } else if (cs.hasBlocks()) {
                textPaint.setColor(0xFF0F172A);
                if (richTwoLine) {
                    textPaint.setTextSize(Math.max(6.5f, ry * 0.42f));
                    canvas.drawText("#" + cid, cx, cy - ry * 0.16f, textPaint);
                    textPaint.setTextSize(Math.max(7.8f, ry * 0.54f));
                    canvas.drawText("×" + cs.topCount(), cx, cy + ry * 0.44f, textPaint);
                } else {
                    textPaint.setTextSize(Math.max(7.5f, ry * 0.78f));
                    canvas.drawText(String.valueOf(cs.topCount()), cx, cy + ry * 0.30f, textPaint);
                }
            } else {
                textPaint.setColor(0xFF94A3B8);
                textPaint.setTextSize(Math.max(6.8f, ry * 0.48f));
                canvas.drawText("#" + cid, cx, cy + ry * 0.22f, textPaint);
            }
        }
    }
}
