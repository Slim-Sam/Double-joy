package ru.doublejoy.solver;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ServiceInfo;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.DisplayMetrics;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Non-Overlapping Variant 4 ("Combo") Floating Overlay + MediaProjection Service (Android 8..15).
 *
 * Architecture:
 * 1) arTargetsView (FLAG_NOT_TOUCHABLE): 100% touch-through AR target circles 1-2-3 directly over
 *    calibrated target hex cells on the real game board + amber !#cid hex rings on diff cells.
 * 2) rootContainer (Top Dock): Ultra-compact 2-row control header strictly above cell #15, with:
 *    - [↻ Обнов.] 1-tap recalibration & snap-back of AR circles and panels;
 *    - [AR ⇄] 1-tap switcher (Combo: AR + Bottom Strip / AR Only / Top Dock Only);
 *    - [Поле ▼/▲] collapsible interactive 20-cell solver board with tap-to-inspect stack layers;
 *    - [? Сверить] collapsible approval-gated board diff drawer (works solo or side-by-side with Поле!).
 * 3) bottomStripContainer: Draggable horizontal 3-stack strip positioned in the empty valley
 *    between hex board bottom and 3 hand stacks, showing FULL stack layer contents (chip icons + counts
 *    + bottom->top▲ text) and [А ⇄ Б] card order toggle (Default А: fixed over own physical stack).
 */
public final class CaptureOverlayService extends Service {

    public static final String EXTRA_RESULT_CODE = "resultCode";
    public static final String EXTRA_RESULT_DATA = "resultData";
    public static final String ACTION_STOP = "ru.doublejoy.solver.STOP_OVERLAY";

    private static final String CHANNEL_ID = "double_joy_capture_channel";
    private static final String PREFS_NAME = "doublejoy_overlay_prefs";
    private static final String PREF_DISPLAY_MODE = "display_mode";
    private static final String PREF_SORT_BY_STEP = "strip_sort_by_step";
    private static final int NOTIF_ID = 1007;

    private static volatile CaptureOverlayService sActiveService;

    public static boolean isRunning() {
        return sActiveService != null;
    }

    public static void notifyWebStateUpdated() {
        final CaptureOverlayService svc = sActiveService;
        if (svc != null) {
            svc.mainHandler.post(svc::refreshOverlayUi);
        }
    }

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();

    private WindowManager windowManager;
    private int realScreenW = 1080;
    private int realScreenH = 2400;

    // 1. Pass-through full-screen AR canvas (FLAG_NOT_TOUCHABLE)
    private ArTargetsCanvasView arTargetsView;
    private WindowManager.LayoutParams arParams;

    // 2. Top compact header + collapsible Поле / Сверить drawers
    private FrameLayout rootContainer;
    private WindowManager.LayoutParams topParams;
    private LinearLayout expandedCard;
    private ImageView collapsedBubble;
    private boolean isCollapsed = false;
    private boolean isBoardDrawerOpen = false;
    private boolean userMovedTop = false;

    // 3. Bottom horizontal 3-stack strip in the gap between hex board and hand tray
    private LinearLayout bottomStripContainer;
    private WindowManager.LayoutParams bottomParams;
    private boolean userMovedBottom = false;

    // Header UI widgets
    private TextView lblStage;
    private TextView lblScore;
    private Button btnRecalibrate;
    private Button btnArMode;
    private Button btnToggleBoard;
    private Button btnCheckBoard;
    private TextView lblCompactBanner;
    private LinearLayout modeAdviceRow;
    private TextView lblModeAdvice;
    private Button btnApplyModeAdvice;
    private Button btnDismissModeAdvice;
    private LinearLayout topDockedStripHolder;
    private LinearLayout drawerContainer;

    // MediaProjection state
    private MediaProjection mediaProjection;
    private VirtualDisplay virtualDisplay;
    private ImageReader imageReader;
    private int capWidth;
    private int capHeight;
    private int screenDensity;
    private boolean isBusyScanning = false;

    private final MediaProjection.Callback projectionCallback = new MediaProjection.Callback() {
        @Override
        public void onStop() {
            mainHandler.post(() -> {
                releaseCaptureResources();
                stopSelf();
            });
        }
    };

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    @SuppressWarnings("deprecation")
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            stopSelf();
            return START_NOT_STICKY;
        }

        sActiveService = this;
        AppState st = AppState.get();
        st.ensureInitialized(getApplicationContext());
        loadOverlayPrefs(st);
        updateScreenMetrics();
        ensureDefaultGeometry();

        startForegroundWithProjectionType();

        if (intent != null && intent.hasExtra(EXTRA_RESULT_CODE)) {
            int resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0);
            Intent resultData = intent.getParcelableExtra(EXTRA_RESULT_DATA);
            if (resultData != null) {
                setupMediaProjection(resultCode, resultData);
            }
        }

        if (rootContainer == null && Settings.canDrawOverlays(this)) {
            createFloatingOverlayWindows();
        }
        refreshOverlayUi();
        return START_STICKY;
    }

    private void loadOverlayPrefs(AppState st) {
        try {
            SharedPreferences sp = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
            st.overlayDisplayMode = sp.getInt(PREF_DISPLAY_MODE, 0) % 3;
            st.overlayStripSortByStep = sp.getBoolean(PREF_SORT_BY_STEP, false);
        } catch (Exception ignored) {}
    }

    private void saveOverlayPrefs(AppState st) {
        try {
            getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                    .edit()
                    .putInt(PREF_DISPLAY_MODE, st.overlayDisplayMode)
                    .putBoolean(PREF_SORT_BY_STEP, st.overlayStripSortByStep)
                    .apply();
        } catch (Exception ignored) {}
    }

    @SuppressWarnings("deprecation")
    private void updateScreenMetrics() {
        windowManager = (WindowManager) getSystemService(Context.WINDOW_SERVICE);
        if (windowManager != null) {
            DisplayMetrics dm = new DisplayMetrics();
            windowManager.getDefaultDisplay().getRealMetrics(dm);
            realScreenW = Math.max(360, dm.widthPixels);
            realScreenH = Math.max(640, dm.heightPixels);
            screenDensity = dm.densityDpi;
        }
    }

    private void ensureDefaultGeometry() {
        AppState st = AppState.get();
        if (st.screenGeo == null || st.screenGeo.stageNo != st.stageNo || st.screenGeo.rotationDeg != st.rotationDeg) {
            Map<Integer, SolverEngine.StageCfg> stages = SolverEngine.getStages(getApplicationContext());
            SolverEngine.StageCfg stg = stages.get(st.stageNo);
            st.screenGeo = SolverEngine.createDefaultScreenGeometry(stg, st.rotationDeg, realScreenW, realScreenH);
        }
    }

    private void startForegroundWithProjectionType() {
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && nm != null) {
            NotificationChannel ch = new NotificationChannel(
                    CHANNEL_ID,
                    "Автозахват «Двойная радость»",
                    NotificationManager.IMPORTANCE_LOW
            );
            ch.setDescription("Плавающая панель захвата экрана и AR-подсказки ходов");
            nm.createNotificationChannel(ch);
        }

        Intent openIntent = new Intent(this, MainActivity.class);
        openIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        int piFlags = Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
                ? PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
                : PendingIntent.FLAG_UPDATE_CURRENT;
        PendingIntent piOpen = PendingIntent.getActivity(this, 1, openIntent, piFlags);

        Intent stopIntent = new Intent(this, CaptureOverlayService.class);
        stopIntent.setAction(ACTION_STOP);
        PendingIntent piStop = PendingIntent.getService(this, 2, stopIntent, piFlags);

        Notification.Builder nb = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? new Notification.Builder(this, CHANNEL_ID)
                : new Notification.Builder(this);

        Notification notif = nb
                .setSmallIcon(R.drawable.ic_launcher)
                .setContentTitle("Решалка «Двойная радость» активна")
                .setContentText("Неперекрывающий AR-оверлей готов к сканированию")
                .setContentIntent(piOpen)
                .addAction(new Notification.Action.Builder(R.drawable.ic_launcher, "Закрыть", piStop).build())
                .setOngoing(true)
                .build();

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIF_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION);
        } else {
            startForeground(NOTIF_ID, notif);
        }
    }

    @SuppressWarnings("deprecation")
    private void setupMediaProjection(int resultCode, Intent resultData) {
        releaseCaptureResources();
        try {
            MediaProjectionManager mpm = (MediaProjectionManager) getSystemService(Context.MEDIA_PROJECTION_SERVICE);
            if (mpm == null) return;
            mediaProjection = mpm.getMediaProjection(resultCode, resultData);
            if (mediaProjection == null) return;

            mediaProjection.registerCallback(projectionCallback, mainHandler);
            updateScreenMetrics();

            double scale = realScreenW > 720 ? (720.0 / realScreenW) : 1.0;
            capWidth = Math.max(360, ((int) Math.round(realScreenW * scale) / 2) * 2);
            capHeight = Math.max(640, ((int) Math.round(realScreenH * scale) / 2) * 2);

            imageReader = ImageReader.newInstance(capWidth, capHeight, PixelFormat.RGBA_8888, 2);
            virtualDisplay = mediaProjection.createVirtualDisplay(
                    "DoubleJoyScreenCap",
                    capWidth,
                    capHeight,
                    screenDensity,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                    imageReader.getSurface(),
                    null,
                    mainHandler
            );
        } catch (Exception e) {
            e.printStackTrace();
            Toast.makeText(this, "Ошибка инициализации захвата: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private float ovScale() {
        float density = Math.max(1.0f, getResources().getDisplayMetrics().density);
        SolverEngine.ScreenBoardGeometry geo = AppState.get().screenGeo;
        int paneW = (geo != null && geo.paneWidthNorm > 0.25)
                ? (int) Math.round(geo.paneWidthNorm * realScreenW)
                : realScreenW;
        float paneDp = paneW / density;
        return Math.max(0.82f, Math.min(1.14f, paneDp / 360.0f));
    }

    private int dp(float v) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics()));
    }

    private int sdp(float v) {
        return dp(v * ovScale());
    }

    private void setScaledTextDp(TextView tv, float sizeDp) {
        tv.setTextSize(TypedValue.COMPLEX_UNIT_DIP, sizeDp * ovScale());
    }

    private GradientDrawable roundedBg(int fillArgb, int strokeArgb, float strokeDp, float radiusDp) {
        GradientDrawable gd = new GradientDrawable();
        gd.setColor(fillArgb);
        gd.setCornerRadius(dp(radiusDp));
        if (strokeDp > 0) gd.setStroke(Math.max(1, dp(strokeDp)), strokeArgb);
        return gd;
    }

    private Button makeSmallBtn(String text, int bgArgb, int strokeArgb, float textSp, int padHdp, int padVdp) {
        Button b = new Button(this);
        b.setAllCaps(false);
        b.setText(text);
        b.setTextColor(Color.WHITE);
        setScaledTextDp(b, textSp);
        b.setTypeface(Typeface.DEFAULT_BOLD);
        b.setPadding(sdp(padHdp), sdp(padVdp), sdp(padHdp), sdp(padVdp));
        b.setMinHeight(0);
        b.setMinimumHeight(0);
        b.setMinWidth(0);
        b.setMinimumWidth(0);
        b.setBackground(roundedBg(bgArgb, strokeArgb, 1.1f, 7f));
        return b;
    }

    private void applyCutoutShortEdges(WindowManager.LayoutParams lp) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
        }
    }

    private int computePaneCenterX(int viewWidthPx) {
        SolverEngine.ScreenBoardGeometry geo = AppState.get().screenGeo;
        double paneCenterNorm = (geo != null) ? (geo.paneLeftNorm + 0.5 * geo.paneWidthNorm) : 0.5;
        int targetLeft = (int) Math.round(paneCenterNorm * realScreenW - viewWidthPx * 0.5);
        return Math.max(dp(4), Math.min(realScreenW - viewWidthPx - dp(4), targetLeft));
    }

    private int computeIdealTopDockY() {
        SolverEngine.ScreenBoardGeometry geo = AppState.get().screenGeo;
        int safeTop = dp(24);
        if (geo != null && geo.calibratedFromScreen) {
            int gameTopPx = (int) Math.round(geo.gameTopNormY * realScreenH);
            int boardTopPx = (int) Math.round(geo.boardTopNormY * realScreenH);
            int maxAllowedTop = Math.max(dp(4), boardTopPx - sdp(82));
            return Math.min(maxAllowedTop, Math.max(safeTop, gameTopPx + dp(4)));
        }
        return safeTop;
    }

    private int computeIdealBottomStripY() {
        SolverEngine.ScreenBoardGeometry geo = AppState.get().screenGeo;
        int stripMeasuredH = (bottomStripContainer != null && bottomStripContainer.getHeight() > dp(32))
                ? bottomStripContainer.getHeight()
                : sdp(72);
        if (geo != null) {
            int boardBotPx = (int) Math.round(geo.boardBotNormY * realScreenH);
            int handTopPx = (int) Math.round(geo.handTopNormY * realScreenH);
            // Strictly anchor bottom edge ABOVE handTopPx so strip NEVER drops onto hand stacks or boosters!
            int maxAllowedY = handTopPx - stripMeasuredH - sdp(3);
            int gapMidY = (boardBotPx + handTopPx) / 2 - stripMeasuredH / 2;
            int y = Math.min(maxAllowedY, gapMidY);
            return Math.max(dp(110), Math.min(realScreenH - dp(150), y));
        }
        return (int) Math.round(realScreenH * 0.618);
    }

    private int computePanelWidthPx() {
        SolverEngine.ScreenBoardGeometry geo = AppState.get().screenGeo;
        int paneW = (geo != null && geo.paneWidthNorm > 0.25)
                ? (int) Math.round(geo.paneWidthNorm * realScreenW)
                : realScreenW;
        return Math.max(dp(260), Math.min(paneW - dp(8), dp(432)));
    }

    @SuppressWarnings("deprecation")
    private void createFloatingOverlayWindows() {
        updateScreenMetrics();
        int overlayType = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                : WindowManager.LayoutParams.TYPE_PHONE;

        // ------------------------------------------------------------------
        // WINDOW 1: Pass-through Full-Screen AR Targets Layer (FLAG_NOT_TOUCHABLE)
        // ------------------------------------------------------------------
        arParams = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                overlayType,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT
        );
        arParams.gravity = Gravity.TOP | Gravity.START;
        applyCutoutShortEdges(arParams);
        arTargetsView = new ArTargetsCanvasView(this);
        windowManager.addView(arTargetsView, arParams);

        // ------------------------------------------------------------------
        // WINDOW 2: Bottom Horizontal 3-Stack Strip (in board-to-hand valley)
        // ------------------------------------------------------------------
        int panelW = computePanelWidthPx();
        bottomParams = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                overlayType,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT
        );
        bottomParams.gravity = Gravity.TOP | Gravity.START;
        bottomParams.x = computePaneCenterX(panelW);
        bottomParams.y = computeIdealBottomStripY();
        applyCutoutShortEdges(bottomParams);

        bottomStripContainer = new LinearLayout(this);
        bottomStripContainer.setOrientation(LinearLayout.VERTICAL);
        bottomStripContainer.setPadding(dp(5), dp(4), dp(5), dp(5));
        bottomStripContainer.setBackground(roundedBg(0xEE0B1324, 0xFF475569, 1.3f, 11f));
        windowManager.addView(bottomStripContainer, bottomParams);

        // ------------------------------------------------------------------
        // WINDOW 3: Top Compact Header + Collapsible Поле / Сверить Drawers
        // ------------------------------------------------------------------
        topParams = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                overlayType,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT
        );
        topParams.gravity = Gravity.TOP | Gravity.START;
        topParams.x = computePaneCenterX(panelW);
        topParams.y = computeIdealTopDockY();
        applyCutoutShortEdges(topParams);

        rootContainer = new FrameLayout(this);

        // Collapsed [SUD] Suicide Squad crest badge
        collapsedBubble = new ImageView(this);
        collapsedBubble.setImageResource(R.drawable.sud_crest);
        collapsedBubble.setScaleType(ImageView.ScaleType.FIT_CENTER);
        collapsedBubble.setPadding(sdp(4), sdp(4), sdp(4), sdp(4));
        collapsedBubble.setBackground(roundedBg(0xF20B1324, 0xFFD97706, 2f, 24f));
        collapsedBubble.setVisibility(View.GONE);
        FrameLayout.LayoutParams bubbleLp = new FrameLayout.LayoutParams(sdp(48), sdp(48));
        rootContainer.addView(collapsedBubble, bubbleLp);

        // Expanded compact top card
        expandedCard = new LinearLayout(this);
        expandedCard.setOrientation(LinearLayout.VERTICAL);
        expandedCard.setPadding(sdp(6), sdp(5), sdp(6), sdp(6));
        expandedCard.setBackground(roundedBg(0xEE0B1324, 0xFF475569, 1.4f, 12f));
        FrameLayout.LayoutParams cardLp = new FrameLayout.LayoutParams(panelW, FrameLayout.LayoutParams.WRAP_CONTENT);
        rootContainer.addView(expandedCard, cardLp);

        // --- Row 1: Stage, Score, [↻ Обнов.], [AR ⇄], [Поле ▼], [↗], [−], [✕] ---
        LinearLayout headerRow = new LinearLayout(this);
        headerRow.setOrientation(LinearLayout.HORIZONTAL);
        headerRow.setGravity(Gravity.CENTER_VERTICAL);

        Button btnPrevStage = makeSmallBtn("‹", 0xFF171F30, 0xFF64748B, 12.0f, 5, 2);
        lblStage = new TextView(this);
        lblStage.setTextColor(0xFFF8FAFC);
        setScaledTextDp(lblStage, 10.6f);
        lblStage.setTypeface(Typeface.DEFAULT_BOLD);
        lblStage.setPadding(sdp(2), 0, sdp(2), 0);
        Button btnNextStage = makeSmallBtn("›", 0xFF171F30, 0xFF64748B, 12.0f, 5, 2);

        lblScore = new TextView(this);
        lblScore.setTextColor(0xFFFDE047);
        setScaledTextDp(lblScore, 10.2f);
        lblScore.setTypeface(Typeface.DEFAULT_BOLD);
        lblScore.setSingleLine(true);
        lblScore.setEllipsize(TextUtils.TruncateAt.END);
        lblScore.setPadding(sdp(3), 0, sdp(2), 0);
        LinearLayout.LayoutParams scoreLp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        lblScore.setLayoutParams(scoreLp);

        btnRecalibrate = makeSmallBtn("↻ Обн.", 0xFF0F2A44, 0xFF38BDF8, 9.5f, 4, 3);
        btnArMode = makeSmallBtn("AR ⇄", 0xFF064E3B, 0xFF34D399, 9.5f, 4, 3);
        btnToggleBoard = makeSmallBtn("Поле ▼", 0xFF171F30, 0xFF64748B, 9.5f, 4, 3);
        Button btnOpenApp = makeSmallBtn("↗", 0xFF1E3A8A, 0xFF60A5FA, 10.6f, 4, 2);
        Button btnCollapse = makeSmallBtn("−", 0xFF334155, 0xFF94A3B8, 11.2f, 4, 2);
        Button btnClose = makeSmallBtn("✕", 0xFF7F1D1D, 0xFFF87171, 10.4f, 4, 2);

        addWithLeftGap(headerRow, btnPrevStage, 0);
        addWithLeftGap(headerRow, lblStage, 0);
        addWithLeftGap(headerRow, btnNextStage, 0);
        headerRow.addView(lblScore);
        addWithLeftGap(headerRow, btnRecalibrate, 2);
        addWithLeftGap(headerRow, btnArMode, 2);
        addWithLeftGap(headerRow, btnToggleBoard, 2);
        addWithLeftGap(headerRow, btnOpenApp, 2);
        addWithLeftGap(headerRow, btnCollapse, 2);
        addWithLeftGap(headerRow, btnClose, 2);
        expandedCard.addView(headerRow);

        // --- Row 2: [✋ Рука] [⏩ Шаг+Рука] [? Сверить] ---
        LinearLayout actionsRow = new LinearLayout(this);
        actionsRow.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams arLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        arLp.topMargin = sdp(4);
        actionsRow.setLayoutParams(arLp);

        Button btnScanHandOnly = makeSmallBtn("✋ Рука", 0xFF0284C7, 0xFF7DD3FC, 10.8f, 5, 4);
        Button btnStepAndHand = makeSmallBtn("⏩ Шаг+Рука", 0xFF059669, 0xFF6EE7B7, 10.8f, 5, 4);
        btnCheckBoard = makeSmallBtn("? Сверить", 0xFF7C2D12, 0xFFFDBA74, 10.8f, 5, 4);

        LinearLayout.LayoutParams b1Lp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 0.90f);
        LinearLayout.LayoutParams b2Lp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.24f);
        b2Lp.leftMargin = sdp(4);
        LinearLayout.LayoutParams b3Lp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 0.98f);
        b3Lp.leftMargin = sdp(4);

        actionsRow.addView(btnScanHandOnly, b1Lp);
        actionsRow.addView(btnStepAndHand, b2Lp);
        actionsRow.addView(btnCheckBoard, b3Lp);
        expandedCard.addView(actionsRow);



        // --- Compact Status / Reroll Warning Banner (shown only when needed so header stays slim!) ---
        lblCompactBanner = new TextView(this);
        lblCompactBanner.setTextColor(0xFFE2E8F0);
        setScaledTextDp(lblCompactBanner, 9.8f);
        lblCompactBanner.setTypeface(Typeface.DEFAULT_BOLD);
        lblCompactBanner.setPadding(sdp(6), sdp(3), sdp(6), sdp(3));
        LinearLayout.LayoutParams cbLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        cbLp.topMargin = sdp(4);
        lblCompactBanner.setLayoutParams(cbLp);
        lblCompactBanner.setVisibility(View.GONE);
        expandedCard.addView(lblCompactBanner);

        // --- Smart Stage & Pre-Deadlock Solver Mode Advisor Row ---
        modeAdviceRow = new LinearLayout(this);
        modeAdviceRow.setOrientation(LinearLayout.HORIZONTAL);
        modeAdviceRow.setGravity(Gravity.CENTER_VERTICAL);
        modeAdviceRow.setPadding(sdp(5), sdp(3), sdp(4), sdp(3));
        LinearLayout.LayoutParams maRowLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        maRowLp.topMargin = sdp(4);
        modeAdviceRow.setLayoutParams(maRowLp);
        modeAdviceRow.setVisibility(View.GONE);

        lblModeAdvice = new TextView(this);
        lblModeAdvice.setTextColor(0xFFFEF3C7);
        setScaledTextDp(lblModeAdvice, 8.9f);
        lblModeAdvice.setTypeface(Typeface.DEFAULT_BOLD);
        lblModeAdvice.setMaxLines(2);
        lblModeAdvice.setEllipsize(TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams maTxtLp = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        maTxtLp.rightMargin = sdp(4);
        modeAdviceRow.addView(lblModeAdvice, maTxtLp);

        btnApplyModeAdvice = makeSmallBtn("Вкл.", 0xFF059669, 0xFF6EE7B7, 8.9f, 5, 3);
        modeAdviceRow.addView(btnApplyModeAdvice);

        btnDismissModeAdvice = makeSmallBtn("✕", 0xFF334155, 0xFF94A3B8, 8.8f, 4, 3);
        LinearLayout.LayoutParams maDisLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        maDisLp.leftMargin = sdp(3);
        modeAdviceRow.addView(btnDismissModeAdvice, maDisLp);
        expandedCard.addView(modeAdviceRow);

        // --- Optional Top-Docked 3-Stack Strip holder (used only in Mode 2: "Верхний док") ---
        topDockedStripHolder = new LinearLayout(this);
        topDockedStripHolder.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams tdLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        tdLp.topMargin = sdp(4);
        topDockedStripHolder.setLayoutParams(tdLp);
        topDockedStripHolder.setVisibility(View.GONE);
        expandedCard.addView(topDockedStripHolder);

        // --- Collapsible Drawers Container: [Поле ▲] and/or [? Сверить ▲] ---
        drawerContainer = new LinearLayout(this);
        drawerContainer.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams dcLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        dcLp.topMargin = dp(5);
        drawerContainer.setLayoutParams(dcLp);
        drawerContainer.setVisibility(View.GONE);
        expandedCard.addView(drawerContainer);

        // Drag listeners
        attachWindowDragListener(headerRow, rootContainer, topParams, false, true);
        attachWindowDragListener(collapsedBubble, rootContainer, topParams, true, true);

        // Button handlers
        btnPrevStage.setOnClickListener(v -> {
            AppState.get().changeStage(getApplicationContext(), AppState.get().stageNo - 1);
            ensureDefaultGeometry();
            snapWindowsToIdealPositions();
            refreshOverlayUi();
            MainActivity.notifyNativeStateUpdated();
        });
        btnNextStage.setOnClickListener(v -> {
            AppState.get().changeStage(getApplicationContext(), AppState.get().stageNo + 1);
            ensureDefaultGeometry();
            snapWindowsToIdealPositions();
            refreshOverlayUi();
            MainActivity.notifyNativeStateUpdated();
        });
        btnRecalibrate.setOnClickListener(v -> triggerRecalibrateAndSnap());
        btnArMode.setOnClickListener(v -> {
            AppState st = AppState.get();
            st.overlayDisplayMode = (st.overlayDisplayMode + 1) % 3;
            saveOverlayPrefs(st);
            String modeMsg = st.overlayDisplayMode == 0
                    ? "Режим Комбо: AR-кружки + нижняя полоска"
                    : (st.overlayDisplayMode == 1 ? "Режим: Только AR-кружки на поле" : "Режим: Единый верхний док");
            Toast.makeText(this, modeMsg, Toast.LENGTH_SHORT).show();
            refreshOverlayUi();
        });
        btnToggleBoard.setOnClickListener(v -> {
            isBoardDrawerOpen = !isBoardDrawerOpen;
            refreshOverlayUi();
        });
        btnOpenApp.setOnClickListener(v -> openMainApp());
        btnCollapse.setOnClickListener(v -> {
            isCollapsed = true;
            refreshOverlayUi();
        });
        btnClose.setOnClickListener(v -> stopSelf());

        btnScanHandOnly.setOnClickListener(v -> triggerScreenScan(false));
        btnStepAndHand.setOnClickListener(v -> triggerScreenScan(true));
        btnCheckBoard.setOnClickListener(v -> {
            if (AppState.get().pendingDiffResult != null) {
                // Toggle close if already open!
                AppState.get().discardPendingDiffs();
                refreshOverlayUi();
                MainActivity.notifyNativeStateUpdated();
            } else {
                triggerBoardDiffScan(false);
            }
        });

        windowManager.addView(rootContainer, topParams);
    }

    private void addWithLeftGap(LinearLayout parent, View child, int leftDp) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        if (leftDp > 0) lp.leftMargin = sdp(leftDp);
        parent.addView(child, lp);
    }

    private void openMainApp() {
        Intent i = new Intent(this, MainActivity.class);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        startActivity(i);
    }

    private void attachWindowDragListener(
            View handle,
            final View windowRoot,
            final WindowManager.LayoutParams params,
            final boolean expandOnTap,
            final boolean isTopWindow
    ) {
        handle.setOnTouchListener(new View.OnTouchListener() {
            private int startX, startY;
            private float touchX, touchY;
            private boolean moved;

            @Override
            public boolean onTouch(View v, MotionEvent ev) {
                switch (ev.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        startX = params.x;
                        startY = params.y;
                        touchX = ev.getRawX();
                        touchY = ev.getRawY();
                        moved = false;
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        int dx = Math.round(ev.getRawX() - touchX);
                        int dy = Math.round(ev.getRawY() - touchY);
                        if (Math.abs(dx) > dp(4) || Math.abs(dy) > dp(4)) moved = true;
                        if (moved) {
                            params.x = Math.max(0, Math.min(realScreenW - dp(48), startX + dx));
                            params.y = Math.max(0, Math.min(realScreenH - dp(44), startY + dy));
                            if (isTopWindow) userMovedTop = true;
                            else userMovedBottom = true;
                            if (windowRoot != null && windowManager != null) {
                                try { windowManager.updateViewLayout(windowRoot, params); } catch (Exception ignored) {}
                            }
                        }
                        return true;
                    case MotionEvent.ACTION_UP:
                        if (!moved && expandOnTap) {
                            isCollapsed = false;
                            refreshOverlayUi();
                        }
                        return true;
                }
                return false;
            }
        });
    }

    private void setAllOverlaysCaptureHidden(boolean hidden) {
        int vis = hidden ? View.INVISIBLE : View.VISIBLE;
        if (arTargetsView != null) arTargetsView.setVisibility(hidden ? View.INVISIBLE : arTargetsView.desiredVisibility);
        if (rootContainer != null) rootContainer.setVisibility(vis);
        if (bottomStripContainer != null) {
            bottomStripContainer.setVisibility(hidden ? View.INVISIBLE : bottomStripDesiredVisibility());
        }
    }

    private int bottomStripDesiredVisibility() {
        AppState st = AppState.get();
        return (!isCollapsed && st.overlayDisplayMode == 0) ? View.VISIBLE : View.GONE;
    }

    private void snapWindowsToIdealPositions() {
        updateScreenMetrics();
        int panelW = computePanelWidthPx();
        userMovedTop = false;
        userMovedBottom = false;
        if (expandedCard != null) {
            FrameLayout.LayoutParams clp = (FrameLayout.LayoutParams) expandedCard.getLayoutParams();
            if (clp != null && clp.width != panelW) {
                clp.width = panelW;
                expandedCard.setLayoutParams(clp);
            }
        }
        if (topParams != null && rootContainer != null && windowManager != null) {
            topParams.x = computePaneCenterX(panelW);
            topParams.y = computeIdealTopDockY();
            try { windowManager.updateViewLayout(rootContainer, topParams); } catch (Exception ignored) {}
        }
        if (bottomParams != null && bottomStripContainer != null && windowManager != null) {
            bottomParams.x = computePaneCenterX(panelW);
            bottomParams.y = computeIdealBottomStripY();
            try { windowManager.updateViewLayout(bottomStripContainer, bottomParams); } catch (Exception ignored) {}
        }
    }

    private interface DualFrameCallback {
        void onDualCaptured(Bitmap uiBmp, Bitmap cleanBmp);
    }

    /**
     * Captures TWO screenshots per logged action:
     * 1) uiBmp — BEFORE hiding our floating app overlay (so log review shows exact live UI state);
     * 2) cleanBmp — AFTER hiding our floating app overlay (clean game frame for CV analysis & log).
     */
    private void captureUiThenCleanFrame(final int cleanDelayMs, final DualFrameCallback cb) {
        Bitmap firstTryUi = acquireCurrentScreenBitmap();
        if (firstTryUi != null) {
            proceedToCleanGrab(firstTryUi, cleanDelayMs, cb);
            return;
        }
        // Force a 1-frame SurfaceFlinger composite while our overlay is STILL visible
        if (rootContainer != null) rootContainer.invalidate();
        if (bottomStripContainer != null) bottomStripContainer.invalidate();
        mainHandler.postDelayed(() -> {
            Bitmap retryUi = acquireCurrentScreenBitmap();
            proceedToCleanGrab(retryUi, cleanDelayMs, cb);
        }, 38);
    }

    private void proceedToCleanGrab(final Bitmap uiBmp, final int cleanDelayMs, final DualFrameCallback cb) {
        setAllOverlaysCaptureHidden(true);
        mainHandler.postDelayed(() -> grabCleanStep(uiBmp, 0, cb), cleanDelayMs);
    }

    private void grabCleanStep(final Bitmap uiBmp, final int attempt, final DualFrameCallback cb) {
        Bitmap cleanBmp = acquireCurrentScreenBitmap();
        if (cleanBmp == null && attempt < 2) {
            mainHandler.postDelayed(() -> grabCleanStep(uiBmp, attempt + 1, cb), 85);
            return;
        }
        setAllOverlaysCaptureHidden(false);
        cb.onDualCaptured(uiBmp, cleanBmp);
    }

    /**
     * [↻ Обн.] — Re-detects screen hex board geometry in ~80 ms, snaps AR target circles 1-2-3
     * right back onto true hex centers, and resets any accidentally shifted top/bottom strips!
     */
    private void triggerRecalibrateAndSnap() {
        if (isBusyScanning) return;
        if (imageReader == null || mediaProjection == null) {
            ensureDefaultGeometry();
            snapWindowsToIdealPositions();
            refreshOverlayUi();
            Toast.makeText(this, "Позиции панелей сброшены по умолчанию", Toast.LENGTH_SHORT).show();
            return;
        }
        isBusyScanning = true;
        captureUiThenCleanFrame(115, this::handleDualRecalibrate);
    }

    private void handleDualRecalibrate(final Bitmap uiBmp, final Bitmap captured) {
        if (captured == null) {
            if (uiBmp != null) { try { uiBmp.recycle(); } catch (Exception ignored) {} }
            isBusyScanning = false;
            snapWindowsToIdealPositions();
            refreshOverlayUi();
            Toast.makeText(this, "Панели выровнены (слегка коснитесь игры для точного кадра)", Toast.LENGTH_SHORT).show();
            return;
        }
        final Bitmap finalBmp = captured;
        worker.execute(() -> {
            try {
                AppState st = AppState.get();
                st.ensureInitialized(getApplicationContext());
                String uiScreenId = (uiBmp != null && st.saveUiScreenshots)
                        ? LogScreenStore.saveScreenBitmap(getApplicationContext(), uiBmp, "КАЛИБР_ВИД") : null;
                String screenId = LogScreenStore.saveScreenBitmap(getApplicationContext(), finalBmp, "КАЛИБР");
                Map<Integer, SolverEngine.StageCfg> stages = SolverEngine.getStages(getApplicationContext());
                SolverEngine.ScreenBoardGeometry geo = SolverEngine.detectScreenBoardGeometry(
                        finalBmp, st.stageNo, st.rotationDeg, st.userCalibration, st.customPalette, stages
                );
                final int manualRot;
                synchronized (st) {
                    st.screenGeo = geo;
                    manualRot = st.rotationDeg;
                    st.enqueueLogEvent(
                            "cv",
                            "КАЛИБР",
                            "↻ Привязка геометрии экрана (Этап " + st.stageNo
                                    + (manualRot != 0 ? (", ручной угол " + manualRot + "°") : "") + ")",
                            null,
                            false,
                            screenId,
                            uiScreenId
                    );
                    st.hasPendingNativeUpdate = true;
                }
                if (uiBmp != null) { try { uiBmp.recycle(); } catch (Exception ignored) {} }
                finalBmp.recycle();
                mainHandler.post(() -> {
                    isBusyScanning = false;
                    snapWindowsToIdealPositions();
                    refreshOverlayUi();
                    MainActivity.notifyNativeStateUpdated();
                    String rotNote = manualRot != 0 ? (" · ракурс " + manualRot + "°") : "";
                    Toast.makeText(this, "✓ Кружки 1–2–3 и полоски привязаны" + rotNote, Toast.LENGTH_SHORT).show();
                });
            } catch (Exception e) {
                e.printStackTrace();
                if (uiBmp != null) { try { uiBmp.recycle(); } catch (Exception ignored) {} }
                try { finalBmp.recycle(); } catch (Exception ignored) {}
                mainHandler.post(() -> {
                    isBusyScanning = false;
                    snapWindowsToIdealPositions();
                    refreshOverlayUi();
                });
            }
        });
    }

    private void triggerScreenScan(final boolean advancePrevPlanFirst) {
        if (isBusyScanning) return;
        if (imageReader == null || mediaProjection == null) {
            Toast.makeText(this, "Разрешение на захват не активно — запустите оверлей из приложения", Toast.LENGTH_SHORT).show();
            return;
        }
        isBusyScanning = true;
        captureUiThenCleanFrame(135, (uiBmp, cleanBmp) -> {
            AppState.get().pendingDiffResult = null;
            processCapturedSolveFrames(advancePrevPlanFirst, uiBmp, cleanBmp);
        });
    }

    private void triggerBoardDiffScan(final boolean mergeWithPrevAngle) {
        if (isBusyScanning) return;
        if (imageReader == null || mediaProjection == null) {
            Toast.makeText(this, "Разрешение на захват не активно — запустите оверлей из приложения", Toast.LENGTH_SHORT).show();
            return;
        }
        isBusyScanning = true;
        captureUiThenCleanFrame(145, (uiBmp, cleanBmp) -> processCapturedBoardDiffFrames(mergeWithPrevAngle, uiBmp, cleanBmp));
    }

    private Bitmap acquireCurrentScreenBitmap() {
        Bitmap captured = null;
        try {
            Image image = imageReader != null ? imageReader.acquireLatestImage() : null;
            if (image != null) {
                int width = image.getWidth();
                int height = image.getHeight();
                Image.Plane[] planes = image.getPlanes();
                ByteBuffer buffer = planes[0].getBuffer();
                int pixelStride = planes[0].getPixelStride();
                int rowStride = planes[0].getRowStride();
                int rowPadding = rowStride - pixelStride * width;

                Bitmap padded = Bitmap.createBitmap(
                        width + Math.max(0, rowPadding / pixelStride),
                        height,
                        Bitmap.Config.ARGB_8888
                );
                padded.copyPixelsFromBuffer(buffer);
                image.close();

                if (padded.getWidth() == width) {
                    captured = padded;
                } else {
                    captured = Bitmap.createBitmap(padded, 0, 0, width, height);
                    padded.recycle();
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return captured;
    }

    private void processCapturedBoardDiffFrames(
            final boolean mergeWithPrevAngle,
            final Bitmap uiBmp,
            final Bitmap captured
    ) {
        if (captured == null) {
            if (uiBmp != null) { try { uiBmp.recycle(); } catch (Exception ignored) {} }
            isBusyScanning = false;
            Toast.makeText(this, "⚠ Кадр ещё не обновился — слегка коснитесь игры и нажмите ещё раз", Toast.LENGTH_SHORT).show();
            return;
        }

        final Bitmap finalBmp = captured;
        worker.execute(() -> {
            try {
                AppState st = AppState.get();
                st.ensureInitialized(getApplicationContext());
                String baseTag = mergeWithPrevAngle ? "ДОСКАН" : "СВЕРКА";
                String uiScreenId = (uiBmp != null && st.saveUiScreenshots && !areBitmapsVisuallyIdentical(uiBmp, finalBmp))
                        ? LogScreenStore.saveScreenBitmap(getApplicationContext(), uiBmp, baseTag + "_ВИД") : null;
                String screenId = LogScreenStore.saveScreenBitmap(
                        getApplicationContext(), finalBmp, baseTag
                );
                Map<Integer, SolverEngine.StageCfg> stages = SolverEngine.getStages(getApplicationContext());

                SolverEngine.ScreenBoardGeometry geo = SolverEngine.detectScreenBoardGeometry(
                        finalBmp, st.stageNo, st.rotationDeg, st.userCalibration, st.customPalette, stages
                );

                synchronized (st) {
                    st.screenGeo = geo;
                    SolverEngine.BoardDiffResult prevRes = mergeWithPrevAngle ? st.pendingDiffResult : null;
                    SolverEngine.BoardDiffResult diffRes = SolverEngine.computeOrMergeBoardDiffFromBitmap(
                            finalBmp, st.cells, st.stageNo, st.curScore, st.rotationDeg,
                            st.userCalibration, st.customPalette, stages, prevRes,
                            st.waits, st.lastPlan, st.causalHistory
                    );
                    diffRes.lastScreenId = screenId != null ? screenId : "";
                    diffRes.uiScreenId = uiScreenId != null ? uiScreenId : "";
                    if (st.screenGeo != null) st.screenGeo.rotationDeg = st.rotationDeg;

                    if (diffRes.invalidFrame && prevRes != null) {
                        final String warnMsg = diffRes.occlusionGuidanceRu;
                        st.enqueueLogEvent(
                                "cv",
                                "СВЕРКА",
                                "⚠ Пропущен пустой/чёрный кадр при доскане — сохранён предыдущий скан сверки",
                                null,
                                false,
                                screenId,
                                uiScreenId
                        );
                        mainHandler.post(() -> Toast.makeText(
                                CaptureOverlayService.this,
                                (warnMsg != null && !warnMsg.isEmpty()) ? warnMsg : "⚠ Поле игры не видно на кадре!",
                                Toast.LENGTH_LONG
                        ).show());
                    } else {
                        st.pendingDiffResult = diffRes;
                        List<String> diffSubs = new ArrayList<>();
                        if (diffRes.causalDiagnosisRu != null && !diffRes.causalDiagnosisRu.isEmpty()) {
                            diffSubs.add(diffRes.causalDiagnosisRu);
                        }
                        if (diffRes.occlusionGuidanceRu != null && !diffRes.occlusionGuidanceRu.isEmpty()) {
                            diffSubs.add(diffRes.occlusionGuidanceRu);
                        }
                        for (SolverEngine.CellDiff d : diffRes.diffs) {
                            diffSubs.add("• Клетка #" + d.cellId + ": в прилож. ["
                                    + SolverEngine.formatLayersPlain(d.appLayers) + "] ↔ решение ["
                                    + SolverEngine.formatLayersPlain(d.gameLayers) + "]");
                        }
                        String angleTag = diffRes.invalidFrame
                                ? " · ПУСТОЙ КАДР"
                                : (!diffRes.detectedTurnBadge.isEmpty()
                                        ? (" · поворот " + diffRes.detectedTurnBadge)
                                        : ((diffRes.scannedAngles != null && diffRes.scannedAngles.size() > 1)
                                                ? (" · ракурсов: " + diffRes.scannedAngles.size()) : ""));
                        st.enqueueLogEvent(
                                "cv",
                                "СВЕРКА",
                                "🔍 " + (mergeWithPrevAngle ? "Досканирование ракурса" : "Сверка поля с игрой")
                                        + " (Этап " + diffRes.stageNo + angleTag
                                        + ", Счёт экрана: " + (diffRes.detectedScore >= 0 ? diffRes.detectedScore : st.curScore)
                                        + ", в решалке: " + st.curScore
                                        + ", отличий: <b>" + diffRes.diffs.size() + "</b>)",
                                diffSubs,
                                false,
                                screenId,
                                uiScreenId
                        );
                    }
                    st.hasPendingNativeUpdate = true;
                }
                if (uiBmp != null) { try { uiBmp.recycle(); } catch (Exception ignored) {} }
                finalBmp.recycle();
            } catch (Exception e) {
                e.printStackTrace();
                if (uiBmp != null) { try { uiBmp.recycle(); } catch (Exception ignored) {} }
                try { finalBmp.recycle(); } catch (Exception ignored) {}
            } finally {
                mainHandler.post(() -> {
                    isBusyScanning = false;
                    if (!userMovedBottom) autoAdjustBottomStripIfCalibrated();
                    refreshOverlayUi();
                    MainActivity.notifyNativeStateUpdated();
                });
            }
        });
    }

    private void processCapturedSolveFrames(
            final boolean advancePrevPlanFirst,
            final Bitmap uiBmp,
            final Bitmap captured
    ) {
        if (captured == null) {
            if (uiBmp != null) { try { uiBmp.recycle(); } catch (Exception ignored) {} }
            isBusyScanning = false;
            Toast.makeText(this, "⚠ Кадр ещё не обновился — слегка коснитесь игры и нажмите ещё раз", Toast.LENGTH_SHORT).show();
            return;
        }

        final Bitmap finalBmp = captured;
        worker.execute(() -> {
            try {
                AppState st = AppState.get();
                st.ensureInitialized(getApplicationContext());
                String baseTag = advancePrevPlanFirst ? "ШАГ_РУКА" : "РУКА";
                String uiScreenId = (uiBmp != null && st.saveUiScreenshots && !areBitmapsVisuallyIdentical(uiBmp, finalBmp))
                        ? LogScreenStore.saveScreenBitmap(getApplicationContext(), uiBmp, baseTag + "_ВИД") : null;
                String screenId = LogScreenStore.saveScreenBitmap(
                        getApplicationContext(), finalBmp, baseTag
                );
                Map<Integer, SolverEngine.StageCfg> stages = SolverEngine.getStages(getApplicationContext());

                // Task #5: Stage scanning happens ONLY on «✋ Рука» (!advancePrevPlanFirst).
                // On «⏩ Шаг+Рука» (advancePrevPlanFirst == true), current stage is strictly locked!
                boolean allowStageScan = !advancePrevPlanFirst;
                SolverEngine.VisionOutput vo = SolverEngine.analyzeHeaderAndHandBitmap(
                        finalBmp, st.customPalette, stages, allowStageScan
                );
                int targetGeoStage = (allowStageScan && vo.detectedStageNo >= 1 && stages.containsKey(vo.detectedStageNo))
                        ? vo.detectedStageNo : st.stageNo;
                SolverEngine.ScreenBoardGeometry geo = SolverEngine.detectScreenBoardGeometry(
                        finalBmp, targetGeoStage, st.rotationDeg, st.userCalibration, st.customPalette, stages
                );

                synchronized (st) {
                    st.stateSeq++;
                    st.screenGeo = geo;
                    boolean stageSwitched = false;
                    if (allowStageScan && vo.detectedStageNo >= 1 && stages.containsKey(vo.detectedStageNo) && vo.detectedStageNo != st.stageNo) {
                        st.stageNo = vo.detectedStageNo;
                        st.rotationDeg = 0;
                        if (st.screenGeo != null) st.screenGeo.rotationDeg = 0;
                        int initSc = Math.max(0, vo.detectedScore);
                        st.curScore = initSc;
                        st.cells = SolverEngine.createStageBoard(stages.get(st.stageNo), initSc);
                        st.lastPlan = null;
                        st.causalHistory.clear();
                        st.scoreMismatchWarning = "";
                        stageSwitched = true;
                        st.enqueueLogEvent(
                                "save",
                                "ЭТАП",
                                "🔄 Автопереход на Этап " + st.stageNo + " (Счёт " + st.curScore + ")",
                                null,
                                true,
                                screenId,
                                uiScreenId
                        );
                    } else if (allowStageScan && vo.detectedScore == 0 && st.curScore > 0) {
                        SolverEngine.StageCfg curStg = stages.get(st.stageNo);
                        if (curStg != null) {
                            st.cells = SolverEngine.createStageBoard(curStg, 0);
                        }
                        st.curScore = 0;
                        st.rotationDeg = 0;
                        if (st.screenGeo != null) st.screenGeo.rotationDeg = 0;
                        st.lastPlan = null;
                        st.causalHistory.clear();
                        st.scoreMismatchWarning = "";
                        stageSwitched = true;
                        st.enqueueLogEvent(
                                "save",
                                "ЭТАП",
                                "🔄 Перезапуск Этапа " + st.stageNo + " с 0 очков",
                                null,
                                true,
                                screenId,
                                uiScreenId
                        );
                    }

                    SolverEngine.StageCfg stg = stages.get(st.stageNo);
                    if (stg == null) stg = stages.get(1);

                    // Snapshot pre-step board, hand, score & plan BEFORE advancing moves (for causal history!)
                    SolverEngine.CellState[] histBoardBefore = st.cells != null ? SolverEngine.cloneBoard(st.cells) : null;
                    int histScoreBefore = st.curScore;
                    Map<Integer, List<SolverEngine.Layer>> histHandBefore = new HashMap<>();
                    for (int sid = 1; sid <= 3; sid++) {
                        List<SolverEngine.Layer> cp = new ArrayList<>();
                        List<SolverEngine.Layer> src = st.waits.get(sid);
                        if (src != null) for (SolverEngine.Layer l : src) cp.add(l.copy());
                        histHandBefore.put(sid, cp);
                    }
                    SolverEngine.SolvePlan histPlanBefore = st.lastPlan;

                    int simulatedAfterMoveScore = -1;
                    if (!stageSwitched && advancePrevPlanFirst) {
                        if (st.lastPlan != null && st.lastPlan.steps != null && !st.lastPlan.steps.isEmpty()) {
                            int prevSc = st.curScore;
                            List<String> moveSubs = new ArrayList<>();
                            for (SolverEngine.StepMove sm : st.lastPlan.steps) {
                                String gainTxt = sm.gainedScore > 0 ? ("+" + sm.gainedScore + " очк.") : "без сброса";
                                String line = "Слот #" + sm.slotId + " (" + SolverEngine.formatLayersPlain(sm.cardLayers)
                                        + ") -> Клетка #" + sm.targetCellId + " | " + gainTxt
                                        + " | Счёт: " + prevSc + " -> " + sm.scoreAfter + "/" + stg.targetScore;
                                if (sm.unlockedCells != null && !sm.unlockedCells.isEmpty()) {
                                    line += " | Открыты замки: " + sm.unlockedCells.toString();
                                }
                                moveSubs.add(line);
                                prevSc = sm.scoreAfter;
                            }
                            int beforeRoundScore = st.curScore;
                            st.applyLastPlanMoves();
                            simulatedAfterMoveScore = st.curScore;
                            String scrHdrNote = vo.detectedScore >= 0 ? (" | Счёт экрана: " + vo.detectedScore) : "";
                            st.enqueueLogEvent(
                                    "move",
                                    "ХОД",
                                    "⏩ Выполнен раунд из " + moveSubs.size() + " ход(ов) | Счёт: <b>"
                                            + beforeRoundScore + " → " + st.curScore + "</b>/" + stg.targetScore + scrHdrNote,
                                    moveSubs,
                                    true,
                                    screenId,
                                    uiScreenId
                            );
                        } else {
                            st.applyLastPlanMoves();
                        }
                    }

                    int prevAppScore = st.curScore;
                    if (vo.detectedScore >= 0) {
                        st.curScore = vo.detectedScore;
                    }

                    if (st.cells == null) {
                        st.cells = SolverEngine.createStageBoard(stg, st.curScore);
                    } else {
                        for (int cid = 1; cid <= SolverEngine.MAX_POS_COUNT; cid++) {
                            SolverEngine.CellState cs = st.cells[cid];
                            if (cs != null && cs.exist == 1) {
                                if (cs.lockScore > 0 && st.curScore >= cs.lockScore) cs.lockScore = 0;
                                if (cs.lockCardScore > 0 && st.curScore >= cs.lockCardScore) cs.lockCardScore = 0;
                            }
                        }
                    }

                    // Measure per-step stack heights & 5x5 cell patches on cleanBmp and push CausalHistoryStep!
                    SolverEngine.VisionOutput stepBoardVo = SolverEngine.analyzeScreenshotBitmap(
                            finalBmp, stg, st.curScore, st.rotationDeg, st.userCalibration, st.customPalette, stages
                    );
                    if (advancePrevPlanFirst && histBoardBefore != null) {
                        st.pushCausalHistoryStep(SolverEngine.CausalHistoryStep.fromVision(
                                (int) st.stateSeq, "ХОД", histScoreBefore, st.curScore, vo.detectedScore,
                                histBoardBefore, st.cells, histHandBefore, histPlanBefore, stepBoardVo, st.rotationDeg
                        ));
                    }

                    // Auto-pickup board stacks when pressing «✋ Рука» on a freshly-switched stage or blank app board,
                    // OR auto-sync partial mid-round moves (1-2 played cards or safe prefix before 🔄 Перемешать)!
                    SolverEngine.BoardDiffResult autoDiff = null;
                    if (allowStageScan) {
                        int appOccupied = 0;
                        for (int cid = 1; cid <= SolverEngine.MAX_POS_COUNT; cid++) {
                            SolverEngine.CellState cs = st.cells[cid];
                            if (cs != null && cs.exist == 1 && !cs.isLocked() && cs.hasBlocks()) appOccupied++;
                        }
                        int nonEmptyWaits = 0;
                        for (int sid = 1; sid <= 3; sid++) {
                            List<SolverEngine.Layer> wl = vo.waits.get(sid);
                            if (wl != null && !wl.isEmpty()) nonEmptyWaits++;
                        }

                        if ((stageSwitched && st.curScore > 0) || (!stageSwitched && appOccupied == 0 && st.curScore > 0)) {
                            SolverEngine.BoardDiffResult initDiff = SolverEngine.computeOrMergeBoardDiffFromBitmap(
                                    finalBmp, st.cells, st.stageNo, st.curScore, st.rotationDeg,
                                    st.userCalibration, st.customPalette, stages, null,
                                    st.waits, st.lastPlan, st.causalHistory
                            );
                            int autoLoaded = 0;
                            if (initDiff != null && initDiff.diffs != null) {
                                for (SolverEngine.CellDiff d : initDiff.diffs) {
                                    if (d.selected && d.gameLayers != null && !d.gameLayers.isEmpty()
                                            && st.cells[d.cellId] != null && st.cells[d.cellId].exist == 1
                                            && !st.cells[d.cellId].isLocked()
                                            && st.cells[d.cellId].layers.isEmpty()) {
                                        for (SolverEngine.Layer l : d.gameLayers) {
                                            st.cells[d.cellId].layers.add(l.copy());
                                        }
                                        st.cells[d.cellId].normalize();
                                        autoLoaded++;
                                    }
                                }
                            }
                            if (autoLoaded > 0) {
                                st.enqueueLogEvent(
                                        "cv",
                                        "АВТО-ПОЛЕ",
                                        "📋 Считаны стопки на поле игры при скане руки: <b>" + autoLoaded + " кл.</b>",
                                        null,
                                        true,
                                        screenId,
                                        uiScreenId
                                );
                            }
                        } else if (!stageSwitched && appOccupied > 0
                                && (vo.detectedScore < 0 || vo.detectedScore == prevAppScore)
                                && tryAutoSyncHammerOnHandScan(st, stepBoardVo, finalBmp, stg, stages, screenId, uiScreenId)) {
                            st.scoreMismatchWarning = "";
                        } else if (nonEmptyWaits >= 1 && nonEmptyWaits <= 2) {
                            boolean synced = tryAutoSyncMidRoundHand(
                                    st, vo, prevAppScore, nonEmptyWaits, finalBmp, stg, stages, screenId
                            );
                            if (synced) {
                                st.scoreMismatchWarning = "";
                            } else {
                                st.scoreMismatchWarning = "⚠ В руке " + nonEmptyWaits + " из 3 стопок! Примените решение в сверке";
                                autoDiff = SolverEngine.computeOrMergeBoardDiffFromBitmap(
                                        finalBmp, st.cells, st.stageNo, st.curScore, st.rotationDeg,
                                        st.userCalibration, st.customPalette, stages, null,
                                        st.waits, st.lastPlan, st.causalHistory
                                );
                                if (autoDiff != null) {
                                    autoDiff.lastScreenId = screenId != null ? screenId : "";
                                    autoDiff.uiScreenId = uiScreenId != null ? uiScreenId : "";
                                }
                            }
                        } else if (nonEmptyWaits == 3
                                && st.lastPlan != null
                                && st.lastPlan.partialBeforeReroll
                                && !st.lastPlan.steps.isEmpty()
                                && vo.detectedScore >= 0
                                && vo.detectedScore == st.lastPlan.finalScore
                                && vo.detectedScore > prevAppScore) {
                            st.stateSeq++;
                            SolverEngine.StepMove lastPrefixStep = st.lastPlan.steps.get(st.lastPlan.steps.size() - 1);
                            st.cells = SolverEngine.cloneBoard(lastPrefixStep.boardAfter);
                            st.curScore = lastPrefixStep.scoreAfter;
                            List<String> prefSubs = new ArrayList<>();
                            int pSc = prevAppScore;
                            for (SolverEngine.StepMove sm : st.lastPlan.steps) {
                                prefSubs.add("Слот #" + sm.slotId + " (" + SolverEngine.formatLayersPlain(sm.cardLayers)
                                        + ") -> Клетка #" + sm.targetCellId + " | Счёт: " + pSc + " -> " + sm.scoreAfter);
                                pSc = sm.scoreAfter;
                            }
                            st.enqueueLogEvent(
                                    "move",
                                    "АВТО-ШАГ",
                                    "⏩ Автоучёт спасительного шага перед 🔄 Перемешать: <b>"
                                            + prevAppScore + " → " + st.curScore + "</b>/" + stg.targetScore,
                                    prefSubs,
                                    true,
                                    screenId,
                                    uiScreenId
                            );
                            st.scoreMismatchWarning = "";
                        } else {
                            st.scoreMismatchWarning = "";
                        }
                    }

                    // Instant Score-Desync Detector on «⏩ Шаг+Рука» (now powered by Causal History Engine!)
                    if (!stageSwitched && advancePrevPlanFirst && simulatedAfterMoveScore >= 0 && vo.detectedScore >= 0) {
                        if (vo.detectedScore != simulatedAfterMoveScore) {
                            st.scoreMismatchWarning = "⚠ Счёт игры (" + vo.detectedScore + ") ≠ расчёту (" + simulatedAfterMoveScore
                                    + ")! Подтвердите решение в «? Сверить»";
                            autoDiff = SolverEngine.computeOrMergeBoardDiffFromBitmap(
                                    finalBmp, st.cells, st.stageNo, st.curScore, st.rotationDeg,
                                    st.userCalibration, st.customPalette, stages, null,
                                    vo.waits, null, st.causalHistory
                            );
                            if (autoDiff != null) {
                                autoDiff.lastScreenId = screenId != null ? screenId : "";
                                autoDiff.uiScreenId = uiScreenId != null ? uiScreenId : "";
                            }
                            st.enqueueLogEvent(
                                    "cv",
                                    "РАССИНХРОН",
                                    "⚠ Счёт в игре (<b>" + vo.detectedScore + "</b>) не совпал с расчётом (<b>"
                                            + simulatedAfterMoveScore + "</b>) — найдено решение в сверке",
                                    null,
                                    false,
                                    screenId,
                                    uiScreenId
                            );
                        } else {
                            st.scoreMismatchWarning = "";
                        }
                    }

                    st.waits = vo.waits;
                    st.pendingDiffResult = (autoDiff != null && autoDiff.diffs != null && !autoDiff.diffs.isEmpty()) ? autoDiff : null;
                    st.lastScreenshotDataUri = null;
                    st.lastCvAnalysisJson = null;
                    st.justScannedStage = allowStageScan;
                    if (stageSwitched || allowStageScan) {
                        st.dismissedModeAdviceKey = "";
                    }
                    int emptyPlaceableNow = 0;
                    for (int cid = 1; cid <= SolverEngine.MAX_POS_COUNT; cid++) {
                        if (st.cells[cid] != null && st.cells[cid].isEmptyPlaceable()) emptyPlaceableNow++;
                    }
                    String effectiveMode = st.mode != null ? st.mode : "balanced";
                    if ("balanced".equals(effectiveMode) && emptyPlaceableNow <= 5) {
                        effectiveMode = "survival";
                    }
                    st.lastPlan = SolverEngine.solveBestPlan(
                            st.cells, st.waits, st.curScore, stg.targetScore, effectiveMode, st.stageNo, st.justScannedStage
                    );
                    if (!advancePrevPlanFirst) {
                        st.pushCausalHistoryStep(SolverEngine.CausalHistoryStep.fromVision(
                                (int) st.stateSeq, "РУКА", st.curScore, st.curScore, vo.detectedScore,
                                st.cells, st.cells, st.waits, st.lastPlan, stepBoardVo, st.rotationDeg
                        ));
                    }

                    List<String> logSubs = new ArrayList<>();
                    String s1 = SolverEngine.formatLayersPlain(st.waits.get(1));
                    String s2 = SolverEngine.formatLayersPlain(st.waits.get(2));
                    String s3 = SolverEngine.formatLayersPlain(st.waits.get(3));
                    logSubs.add("Слот #1: " + s1 + " | Слот #2: " + s2 + " | Слот #3: " + s3);
                    if (st.lastPlan != null && st.lastPlan.deadlockStopAll) {
                        logSubs.add("🛑 СТОП! Безвыходный тупик (пусто кл.: 0) — ПАЧКИ НЕ СТАВИТЬ, жмите 🔄 Перемешать"
                                + (st.lastPlan.hammerCellId > 0 ? (" или 🔨 #" + st.lastPlan.hammerCellId) : ""));
                        if (st.lastPlan.hammerCellId > 0 && st.lastPlan.hammerReasonRu != null && !st.lastPlan.hammerReasonRu.isEmpty()) {
                            logSubs.add("💡 " + st.lastPlan.hammerReasonRu);
                        }
                    } else if (st.lastPlan != null && !st.lastPlan.steps.isEmpty()) {
                        StringBuilder psb = new StringBuilder(st.lastPlan.partialBeforeReroll
                                ? "💡 Безопасный частичный план (ПЕРЕД 🔄): " : "★ Лучший план: ");
                        for (SolverEngine.StepMove sm : st.lastPlan.steps) {
                            psb.append(sm.stepNum).append(") Слот #").append(sm.slotId).append("->#").append(sm.targetCellId).append(" ");
                        }
                        psb.append("| Итог ").append(st.lastPlan.finalScore).append(", Пусто ").append(st.lastPlan.finalEmptyCells);
                        if (st.lastPlan.partialBeforeReroll) psb.append(" [⛔ ДАЛЬШЕ СТОП → ЖМИТЕ 🔄 ПЕРЕМЕШАТЬ!]");
                        else if (st.lastPlan.recommendReroll) psb.append(" [⚠ РЕКОМЕНДОВАН БУСТЕР 🔄]");
                        logSubs.add(psb.toString());
                        if (st.lastPlan.hammerCellId > 0 && st.lastPlan.hammerReasonRu != null && !st.lastPlan.hammerReasonRu.isEmpty()) {
                            logSubs.add("💡 " + st.lastPlan.hammerReasonRu);
                        }
                    }
                    logSubs.add("📋 Поле: " + st.formatBoardSummaryPlain());

                    String detScrInfo = (vo.detectedScore >= 0 ? (", экран: " + vo.detectedScore) : "")
                            + ", режим: " + (st.mode != null ? st.mode : "balanced");
                    st.enqueueLogEvent(
                            "cv",
                            "РУКА",
                            "✋ Скан руки (Этап " + st.stageNo + ", Счёт <b>" + st.curScore + "</b>/" + stg.targetScore
                                    + detScrInfo + "): ⬅" + s1 + " · ⬆" + s2 + " · ➡" + s3,
                            logSubs,
                            true,
                            screenId,
                            uiScreenId
                    );
                    st.hasPendingNativeUpdate = true;
                }
            } catch (Exception e) {
                e.printStackTrace();
            } finally {
                if (uiBmp != null) { try { uiBmp.recycle(); } catch (Exception ignored) {} }
                try { finalBmp.recycle(); } catch (Exception ignored) {}
                mainHandler.post(() -> {
                    isBusyScanning = false;
                    if (!userMovedBottom) autoAdjustBottomStripIfCalibrated();
                    if (!userMovedTop) autoAdjustTopDockIfCalibrated();
                    refreshOverlayUi();
                    MainActivity.notifyNativeStateUpdated();
                });
            }
        });
    }

    /**
     * Exact automatic mid-round move synchronization when the user manually played 1 or 2 cards in the game
     * and then tapped «✋ Рука» (without pressing «⏩ Шаг+Рука»).
     * Uses exact multiset card matching + score verification + lightweight CV cell occupancy verification,
     * preserving 100% of hidden bottom layers on the board!
     */
    private boolean tryAutoSyncMidRoundHand(
            AppState st,
            SolverEngine.VisionOutput vo,
            int prevAppScore,
            int nonEmptyWaits,
            Bitmap finalBmp,
            SolverEngine.StageCfg stg,
            Map<Integer, SolverEngine.StageCfg> stages,
            String screenId
    ) {
        if (st.cells == null || st.waits == null) return false;
        int prevNonEmpty = 0;
        List<List<SolverEngine.Layer>> prevCards = new ArrayList<>();
        for (int sid = 1; sid <= 3; sid++) {
            List<SolverEngine.Layer> wl = st.waits.get(sid);
            if (wl != null && !wl.isEmpty()) {
                prevNonEmpty++;
                prevCards.add(wl);
            }
        }
        int playedCount = prevNonEmpty - nonEmptyWaits;
        if (playedCount < 1 || playedCount > 2) return false;

        boolean[] matchedPrev = new boolean[prevCards.size()];
        for (int sid = 1; sid <= 3; sid++) {
            List<SolverEngine.Layer> curW = vo.waits.get(sid);
            if (curW == null || curW.isEmpty()) continue;
            String curSig = SolverEngine.formatLayersPlain(curW);
            boolean found = false;
            for (int i = 0; i < prevCards.size(); i++) {
                if (!matchedPrev[i] && curSig.equals(SolverEngine.formatLayersPlain(prevCards.get(i)))) {
                    matchedPrev[i] = true;
                    found = true;
                    break;
                }
            }
            if (!found) {
                return false;
            }
        }

        List<List<SolverEngine.Layer>> missingCards = new ArrayList<>();
        for (int i = 0; i < prevCards.size(); i++) {
            if (!matchedPrev[i]) missingCards.add(prevCards.get(i));
        }
        if (missingCards.size() != playedCount) return false;

        int targetScoreNow = (vo.detectedScore >= 0) ? vo.detectedScore : st.curScore;
        SolverEngine.VisionOutput lazyBoardVo = null;

        // 1. Check if the user executed the exact prefix steps[0..playedCount-1] of st.lastPlan!
        if (st.lastPlan != null && st.lastPlan.steps != null && st.lastPlan.steps.size() >= playedCount) {
            boolean cardsMatchPrefix = true;
            boolean[] usedMissing = new boolean[missingCards.size()];
            for (int k = 0; k < playedCount; k++) {
                String stepSig = SolverEngine.formatLayersPlain(st.lastPlan.steps.get(k).cardLayers);
                boolean ok = false;
                for (int m = 0; m < missingCards.size(); m++) {
                    if (!usedMissing[m] && stepSig.equals(SolverEngine.formatLayersPlain(missingCards.get(m)))) {
                        usedMissing[m] = true;
                        ok = true;
                        break;
                    }
                }
                if (!ok) {
                    cardsMatchPrefix = false;
                    break;
                }
            }
            SolverEngine.StepMove endStep = st.lastPlan.steps.get(playedCount - 1);
            if (cardsMatchPrefix && (vo.detectedScore < 0 || endStep.scoreAfter == targetScoreNow)) {
                boolean verified = (endStep.scoreAfter > prevAppScore && vo.detectedScore >= 0);
                if (!verified) {
                    lazyBoardVo = SolverEngine.analyzeScreenshotBitmap(
                            finalBmp, stg, targetScoreNow, st.rotationDeg, st.userCalibration, st.customPalette, stages
                    );
                    verified = true;
                    for (int k = 0; k < playedCount; k++) {
                        int tCid = st.lastPlan.steps.get(k).targetCellId;
                        SolverEngine.CellState expAfter = endStep.boardAfter[tCid];
                        SolverEngine.CellState obsNow = lazyBoardVo.cells[tCid];
                        if (expAfter != null && expAfter.hasBlocks()) {
                            if (obsNow == null || !obsNow.hasBlocks() || obsNow.topColor() != expAfter.topColor()) {
                                verified = false;
                                break;
                            }
                        }
                    }
                }
                if (verified) {
                    st.stateSeq++;
                    st.cells = SolverEngine.cloneBoard(endStep.boardAfter);
                    st.curScore = endStep.scoreAfter;
                    List<String> subs = new ArrayList<>();
                    int pSc = prevAppScore;
                    for (int k = 0; k < playedCount; k++) {
                        SolverEngine.StepMove sm = st.lastPlan.steps.get(k);
                        subs.add("Слот #" + sm.slotId + " (" + SolverEngine.formatLayersPlain(sm.cardLayers)
                                + ") -> Клетка #" + sm.targetCellId + " | Счёт: " + pSc + " -> " + sm.scoreAfter);
                        pSc = sm.scoreAfter;
                    }
                    st.enqueueLogEvent(
                            "move",
                            "АВТО-ШАГ",
                            "⏩ Автоучёт сделанных шагов плана (" + playedCount + " из " + prevNonEmpty
                                    + "): <b>" + prevAppScore + " → " + st.curScore + "</b>/" + stg.targetScore,
                            subs,
                            true,
                            screenId
                    );
                    return true;
                }
            }
        }

        // 2. Fallback when playedCount == 1 and the user placed that 1 card onto a DIFFERENT empty cell!
        if (playedCount == 1) {
            List<SolverEngine.Layer> playedCard = missingCards.get(0);
            if (lazyBoardVo == null) {
                lazyBoardVo = SolverEngine.analyzeScreenshotBitmap(
                        finalBmp, stg, targetScoreNow, st.rotationDeg, st.userCalibration, st.customPalette, stages
                );
            }
            int matchedCellId = -1;
            SolverEngine.SimResult matchedSim = null;
            int candidateCount = 0;
            for (int cid = 1; cid <= SolverEngine.MAX_POS_COUNT; cid++) {
                SolverEngine.CellState beforeCs = st.cells[cid];
                if (beforeCs == null || !beforeCs.isEmptyPlaceable()) continue;
                SolverEngine.SimResult sim = SolverEngine.simulatePlaceCard(st.cells, playedCard, cid, prevAppScore);
                if (vo.detectedScore >= 0 && sim.finalScore != targetScoreNow) continue;

                SolverEngine.CellState expAfter = sim.newCells[cid];
                SolverEngine.CellState obsNow = lazyBoardVo.cells[cid];
                boolean cellMatchesScreen;
                if (expAfter.hasBlocks()) {
                    cellMatchesScreen = (obsNow != null && obsNow.hasBlocks() && obsNow.topColor() == expAfter.topColor());
                } else {
                    cellMatchesScreen = (sim.gainedScore > 0 && (obsNow == null || !obsNow.hasBlocks()));
                }
                if (cellMatchesScreen) {
                    matchedCellId = cid;
                    matchedSim = sim;
                    candidateCount++;
                }
            }
            if (candidateCount == 1 && matchedSim != null) {
                st.stateSeq++;
                st.cells = matchedSim.newCells;
                st.curScore = matchedSim.finalScore;
                List<String> subs = new ArrayList<>();
                subs.add("Пачка (" + SolverEngine.formatLayersPlain(playedCard) + ") -> Клетка #" + matchedCellId
                        + " | Счёт: " + prevAppScore + " -> " + st.curScore);
                st.enqueueLogEvent(
                        "move",
                        "АВТО-ШАГ",
                        "⏩ Автоучёт вашего хода (в Клетку #" + matchedCellId + "): <b>"
                                + prevAppScore + " → " + st.curScore + "</b>/" + stg.targetScore,
                        subs,
                        true,
                        screenId
                );
                return true;
            }
        }
        return false;
    }

    /**
     * Fast 36-point pixel comparison across top/bottom overlay regions.
     * On Android 14+ single-app MediaProjection, the OS excludes floating windows from uiBmp,
     * making uiBmp pixel-identical to cleanBmp — skipping duplicate *_ВИД.jpg cuts ZIP size in half!
     */
    private static boolean areBitmapsVisuallyIdentical(Bitmap a, Bitmap b) {
        if (a == null || b == null) return false;
        int w = a.getWidth(), h = a.getHeight();
        if (w != b.getWidth() || h != b.getHeight() || w < 16 || h < 16) return false;
        double[] ys = {0.06, 0.12, 0.19, 0.72, 0.80, 0.88};
        double[] xs = {0.15, 0.32, 0.50, 0.68, 0.85};
        for (double yf : ys) {
            int py = Math.min(h - 1, Math.max(0, (int) Math.round(yf * h)));
            for (double xf : xs) {
                int px = Math.min(w - 1, Math.max(0, (int) Math.round(xf * w)));
                int ca = a.getPixel(px, py);
                int cb = b.getPixel(px, py);
                if (Math.abs(((ca >> 16) & 0xFF) - ((cb >> 16) & 0xFF)) > 3
                        || Math.abs(((ca >> 8) & 0xFF) - ((cb >> 8) & 0xFF)) > 3
                        || Math.abs((ca & 0xFF) - (cb & 0xFF)) > 3) {
                    return false;
                }
            }
        }
        return true;
    }

    /**
     * Automatically detects when the player smashed 1 stack on the board with the Hammer (🔨 Молоток)
     * and then tapped «✋ Рука» (without needing «? Сверить»!).
     */
    private boolean tryAutoSyncHammerOnHandScan(
            AppState st,
            SolverEngine.VisionOutput stepBoardVo,
            Bitmap finalBmp,
            SolverEngine.StageCfg stg,
            Map<Integer, SolverEngine.StageCfg> stages,
            String screenId,
            String uiScreenId
    ) {
        if (st.cells == null || stepBoardVo == null || stg == null) return false;
        int newlyEmptyCid = -1;
        int newlyEmptyCount = 0;
        int newlyOccupiedCount = 0;
        for (SolverEngine.StageCellCfg sc : stg.cells) {
            int cid = sc.cellId;
            if (stepBoardVo.popupCovered != null && stepBoardVo.popupCovered[cid]) continue;
            SolverEngine.CellState appCs = st.cells[cid];
            SolverEngine.CellState obsCs = stepBoardVo.cells[cid];
            if (appCs == null || appCs.isLocked()) continue;
            boolean appOcc = appCs.hasBlocks();
            boolean obsOcc = obsCs != null && !obsCs.isLocked() && obsCs.hasBlocks();
            if (appOcc && !obsOcc && stepBoardVo.measuredHeightSlices[cid] <= 0.15f) {
                newlyEmptyCid = cid;
                newlyEmptyCount++;
            } else if (!appOcc && obsOcc) {
                newlyOccupiedCount++;
            }
        }
        if (newlyEmptyCount != 1 || newlyOccupiedCount != 0 || newlyEmptyCid <= 0) return false;

        // Confirm via full Causal Engine (H2: 💡 🔨 Молоток очистил #cid) so occlusion/popups never false-trigger!
        SolverEngine.BoardDiffResult hDiff = SolverEngine.computeOrMergeBoardDiffFromBitmap(
                finalBmp, st.cells, st.stageNo, st.curScore, st.rotationDeg,
                st.userCalibration, st.customPalette, stages, null,
                st.waits, st.lastPlan, st.causalHistory
        );
        if (hDiff == null || hDiff.diffs == null || hDiff.diffs.size() != 1) return false;
        SolverEngine.CellDiff d = hDiff.diffs.get(0);
        if (d.cellId != newlyEmptyCid || (d.gameLayers != null && !d.gameLayers.isEmpty())) return false;

        String clearedDesc = SolverEngine.formatLayersPlain(st.cells[newlyEmptyCid].layers);
        st.stateSeq++;
        st.cells[newlyEmptyCid].layers.clear();
        st.enqueueLogEvent(
                "move",
                "АВТО-МОЛОТОК",
                "🔨 Автоучёт бустера «Молоток»: очищена <b>Клетка #" + newlyEmptyCid + "</b> (" + clearedDesc + ")",
                null,
                true,
                screenId,
                uiScreenId
        );
        return true;
    }

    private void autoAdjustBottomStripIfCalibrated() {
        if (bottomParams != null && bottomStripContainer != null && windowManager != null) {
            int panelW = computePanelWidthPx();
            bottomParams.x = computePaneCenterX(panelW);
            bottomParams.y = computeIdealBottomStripY();
            try { windowManager.updateViewLayout(bottomStripContainer, bottomParams); } catch (Exception ignored) {}
        }
    }

    private void autoAdjustTopDockIfCalibrated() {
        if (topParams != null && rootContainer != null && windowManager != null) {
            int panelW = computePanelWidthPx();
            topParams.x = computePaneCenterX(panelW);
            topParams.y = computeIdealTopDockY();
            try { windowManager.updateViewLayout(rootContainer, topParams); } catch (Exception ignored) {}
        }
    }

    private int getChipDrawableId(int colour) {
        switch (colour) {
            case 1: return R.drawable.chip_1;
            case 2: return R.drawable.chip_2;
            case 3: return R.drawable.chip_3;
            case 4: return R.drawable.chip_4;
            case 5: return R.drawable.chip_5;
            case 6: return R.drawable.chip_6;
            case 7: return R.drawable.chip_7;
            case 8: return R.drawable.chip_8;
            default: return R.drawable.chip_1;
        }
    }

    private int getChipPillBg(int col) {
        switch (col) {
            case 1: return 0xEB991B1B;
            case 2: return 0xEB9A3412;
            case 3: return 0xEB854D0E;
            case 4: return 0xEB166534;
            case 5: return 0xEB115E59;
            case 6: return 0xEB1E40AF;
            case 7: return 0xEB6B21A8;
            case 8: return 0xEB9D174D;
            default: return 0xEB334155;
        }
    }

    private int getChipPillBorder(int col) {
        switch (col) {
            case 1: return 0xFFFCA5A5;
            case 2: return 0xFFFDBA74;
            case 3: return 0xFFFDE047;
            case 4: return 0xFF86EFAC;
            case 5: return 0xFF5EEAD4;
            case 6: return 0xFF93C5FD;
            case 7: return 0xFFD8B4FE;
            case 8: return 0xFFF9A8D4;
            default: return 0xFF94A3B8;
        }
    }

    private void refreshOverlayUi() {
        try {
        } catch (Exception ignored) {}
        if (expandedCard == null || rootContainer == null) return;
        AppState st = AppState.get();
        ensureDefaultGeometry();
        Map<Integer, SolverEngine.StageCfg> map = SolverEngine.getStages(getApplicationContext());
        SolverEngine.StageCfg stg = map.get(st.stageNo);
        int target = stg != null ? stg.targetScore : 0;

        // 1. Collapsed vs Expanded visibility
        if (isCollapsed) {
            expandedCard.setVisibility(View.GONE);
            collapsedBubble.setVisibility(View.VISIBLE);
            if (bottomStripContainer != null) bottomStripContainer.setVisibility(View.GONE);
            if (arTargetsView != null) {
                arTargetsView.desiredVisibility = View.GONE;
                arTargetsView.setVisibility(View.GONE);
            }
            try { windowManager.updateViewLayout(rootContainer, topParams); } catch (Exception ignored) {}
            return;
        }

        collapsedBubble.setVisibility(View.GONE);
        expandedCard.setVisibility(View.VISIBLE);

        // 2. Header button labels & active states
        lblStage.setText("Эт." + st.stageNo + (st.rotationDeg != 0 ? ("·" + st.rotationDeg + "°") : ""));
        lblScore.setText(st.curScore + "/" + target);

        String arLabel = st.overlayDisplayMode == 0 ? "AR ⇄"
                : (st.overlayDisplayMode == 1 ? "AR•1 ⇄" : "Док ⇄");
        btnArMode.setText(arLabel);
        btnArMode.setBackground(roundedBg(
                st.overlayDisplayMode == 2 ? 0xFF334155 : 0xFF064E3B,
                st.overlayDisplayMode == 2 ? 0xFF94A3B8 : 0xFF34D399,
                1.1f, 7f));

        btnToggleBoard.setText(isBoardDrawerOpen ? "Поле ▲" : "Поле ▼");
        btnToggleBoard.setBackground(roundedBg(
                isBoardDrawerOpen ? 0xFF2563EB : 0xFF171F30,
                isBoardDrawerOpen ? 0xFF93C5FD : 0xFF64748B,
                isBoardDrawerOpen ? 1.5f : 1.1f, 7f));

        boolean hasDiffOpen = (st.pendingDiffResult != null);
        btnCheckBoard.setText(hasDiffOpen ? "? Сверить ▲" : "? Сверить");
        btnCheckBoard.setBackground(roundedBg(
                hasDiffOpen ? 0xFFB45309 : 0xFF7C2D12,
                hasDiffOpen ? 0xFFFDE047 : 0xFFFDBA74,
                hasDiffOpen ? 1.5f : 1.1f, 7f));

        // 3. Compact status / score-desync / reroll alert banner inside top header
        SolverEngine.SolvePlan plan = st.lastPlan;
        if (st.scoreMismatchWarning != null && !st.scoreMismatchWarning.isEmpty()) {
            lblCompactBanner.setText(st.scoreMismatchWarning);
            lblCompactBanner.setTextColor(0xFFFEF3C7);
            lblCompactBanner.setBackground(roundedBg(0xEE7C2D12, 0xFFFBBF24, 1.4f, 6f));
            lblCompactBanner.setVisibility(View.VISIBLE);
        } else if (plan != null && plan.recommendReroll && plan.rerollReason != null && !plan.rerollReason.isEmpty()) {
            boolean fatal = plan.deadlockStopAll || plan.finalEmptyCells == 0;
            String bannerTxt = plan.rerollReason;
            if (plan.hammerCellId > 0 && plan.hammerReasonRu != null && !plan.hammerReasonRu.isEmpty()) {
                bannerTxt = bannerTxt + "\n" + plan.hammerReasonRu;
            }
            lblCompactBanner.setText(bannerTxt);
            lblCompactBanner.setTextColor(0xFFFFF1F2);
            lblCompactBanner.setBackground(roundedBg(
                    fatal ? 0xEE7F1D1D : (plan.partialBeforeReroll ? 0xEE0F3D3E : 0xEE78350F),
                    fatal ? 0xFFF87171 : (plan.partialBeforeReroll ? 0xFFFDE047 : 0xFFFBBF24),
                    1.4f, 6f));
            lblCompactBanner.setVisibility(View.VISIBLE);
        } else if (plan != null && plan.hammerCellId > 0 && plan.hammerReasonRu != null && !plan.hammerReasonRu.isEmpty()) {
            lblCompactBanner.setText(plan.hammerReasonRu);
            lblCompactBanner.setTextColor(0xFFFAF5FF);
            lblCompactBanner.setBackground(roundedBg(0xEE3B0764, 0xFFC084FC, 1.3f, 6f));
            lblCompactBanner.setVisibility(View.VISIBLE);
        } else if (st.overlayDisplayMode == 1 && plan != null && !plan.steps.isEmpty()) {
            // In "AR Only" mode (no bottom strip), show a crisp 1-line summary right under the buttons!
            StringBuilder sb = new StringBuilder();
            String[] sp = {"", "ЛЕВ#1", "СР#2", "ПРАВ#3"};
            for (SolverEngine.StepMove sm : plan.steps) {
                if (sb.length() > 0) sb.append("  •  ");
                sb.append(sm.stepNum).append(") ").append(sp[sm.slotId]).append("→#").append(sm.targetCellId);
                if (sm.gainedScore > 0) sb.append("(+").append(sm.gainedScore).append(")");
            }
            lblCompactBanner.setText(sb.toString());
            lblCompactBanner.setTextColor(0xFFE0F2FE);
            lblCompactBanner.setBackground(roundedBg(0xDD0F172A, 0xFF38BDF8, 1.0f, 6f));
            lblCompactBanner.setVisibility(View.VISIBLE);
        } else {
            lblCompactBanner.setVisibility(View.GONE);
        }

        // 3b. Smart Stage & Pre-Deadlock Solver Mode Advisor Row
        if (modeAdviceRow != null) {
            SolverEngine.ModeAdvice ma = SolverEngine.computeSolverModeAdvice(
                    st.stageNo, st.cells, st.curScore, target, st.mode, plan, st.justScannedStage
            );
            final String sugM = (ma.suggestedMode != null) ? ma.suggestedMode : "";
            final String adviceKey = st.stageNo + ":" + sugM + ":" + (ma.urgent ? "1" : "0");
            if (!sugM.isEmpty() && !sugM.equals(st.mode) && !adviceKey.equals(st.dismissedModeAdviceKey)) {
                lblModeAdvice.setText(ma.suggestedModeReasonRu);
                lblModeAdvice.setTextColor(ma.urgent ? 0xFFFEF3C7 : 0xFFE0F2FE);
                modeAdviceRow.setBackground(roundedBg(
                        ma.urgent ? 0xEE5C2409 : 0xEE0F2A44,
                        ma.urgent ? 0xFFF59E0B : 0xFF38BDF8,
                        1.2f, 6f));
                btnApplyModeAdvice.setText("Вкл. " + ma.suggestedModeTitleRu);
                final int stgTarget = target;
                btnApplyModeAdvice.setOnClickListener(v -> {
                    worker.execute(() -> {
                        synchronized (st) {
                            st.mode = sugM;
                            st.dismissedModeAdviceKey = adviceKey;
                            if (st.cells != null) {
                                st.lastPlan = SolverEngine.solveBestPlan(
                                        st.cells, st.waits, st.curScore, stgTarget, st.mode, st.stageNo, false
                                );
                            }
                            st.hasPendingNativeUpdate = true;
                        }
                        mainHandler.post(() -> {
                            Toast.makeText(
                                    CaptureOverlayService.this,
                                    "✓ Режим: " + ma.suggestedModeTitleRu + " — ход пересчитан!",
                                    Toast.LENGTH_SHORT
                            ).show();
                            refreshOverlayUi();
                            MainActivity.notifyNativeStateUpdated();
                        });
                    });
                });
                btnDismissModeAdvice.setOnClickListener(v -> {
                    st.dismissedModeAdviceKey = adviceKey;
                    refreshOverlayUi();
                });
                modeAdviceRow.setVisibility(View.VISIBLE);
            } else {
                modeAdviceRow.setVisibility(View.GONE);
            }
        }

        // 4. Populate Horizontal 3-Stack Strip (either in Bottom Valley [mode 0] or Top Dock [mode 2])
        int panelW = computePanelWidthPx();
        if (expandedCard != null) {
            FrameLayout.LayoutParams clp = (FrameLayout.LayoutParams) expandedCard.getLayoutParams();
            if (clp != null && clp.width != panelW) {
                clp.width = panelW;
                expandedCard.setLayoutParams(clp);
            }
        }
        if (st.overlayDisplayMode == 0) {
            topDockedStripHolder.removeAllViews();
            topDockedStripHolder.setVisibility(View.GONE);
            if (bottomStripContainer != null) {
                populateThreeStackStrip(bottomStripContainer, panelW, true);
                bottomStripContainer.setVisibility(View.VISIBLE);
                if (!userMovedBottom && bottomParams != null) {
                    bottomParams.x = computePaneCenterX(panelW);
                    bottomParams.y = computeIdealBottomStripY();
                }
                try { windowManager.updateViewLayout(bottomStripContainer, bottomParams); } catch (Exception ignored) {}
                if (!userMovedBottom) {
                    bottomStripContainer.post(() -> {
                        if (bottomStripContainer == null || bottomParams == null || windowManager == null || userMovedBottom) return;
                        int idealY = computeIdealBottomStripY();
                        if (Math.abs(bottomParams.y - idealY) > dp(2)) {
                            bottomParams.y = idealY;
                            try { windowManager.updateViewLayout(bottomStripContainer, bottomParams); } catch (Exception ignored) {}
                        }
                    });
                }
            }
        } else if (st.overlayDisplayMode == 2) {
            if (bottomStripContainer != null) bottomStripContainer.setVisibility(View.GONE);
            topDockedStripHolder.removeAllViews();
            populateThreeStackStrip(topDockedStripHolder, panelW - dp(12), false);
            topDockedStripHolder.setVisibility(View.VISIBLE);
        } else {
            if (bottomStripContainer != null) bottomStripContainer.setVisibility(View.GONE);
            topDockedStripHolder.removeAllViews();
            topDockedStripHolder.setVisibility(View.GONE);
        }

        // 5. Populate Collapsible Drawers: [Поле ▲] and/or [? Сверить ▲]
        rebuildDrawersUi(st);

        // 6. Refresh Pass-Through AR Targets Canvas (never show AR highlights while «? Сверить» drawer is open!)
        if (arTargetsView != null) {
            boolean needAr = (st.overlayDisplayMode == 0 || st.overlayDisplayMode == 1) && (st.pendingDiffResult == null);
            arTargetsView.desiredVisibility = needAr ? View.VISIBLE : View.GONE;
            arTargetsView.setVisibility(arTargetsView.desiredVisibility);
            arTargetsView.invalidate();
        }

        try { windowManager.updateViewLayout(rootContainer, topParams); } catch (Exception ignored) {}
    }

    /**
     * Renders the 3-card horizontal strip with FULL stack layer contents (icons + ×counts + text▲)
     * and the 1-tap [А ⇄ Б] card ordering switch!
     */
    private void populateThreeStackStrip(LinearLayout host, int targetWidthPx, boolean attachDragToHeader) {
        host.removeAllViews();
        AppState st = AppState.get();
        SolverEngine.SolvePlan plan = st.lastPlan;

        // Micro header bar above the 3 cards: brief status on left + [А ⇄ Б] order toggle on right
        LinearLayout topBar = new LinearLayout(this);
        topBar.setOrientation(LinearLayout.HORIZONTAL);
        topBar.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams tbLp = new LinearLayout.LayoutParams(
                targetWidthPx - dp(10), LinearLayout.LayoutParams.WRAP_CONTENT);
        tbLp.bottomMargin = dp(3);
        topBar.setLayoutParams(tbLp);

        TextView lblMini = new TextView(this);
        setScaledTextDp(lblMini, 9.2f);
        lblMini.setTypeface(Typeface.DEFAULT_BOLD);
        lblMini.setTextColor(0xFFCBD5E1);
        lblMini.setSingleLine(true);
        lblMini.setEllipsize(TextUtils.TruncateAt.END);
        if (plan != null && plan.deadlockStopAll) {
            lblMini.setText("🛑 СТОП! НЕ СТАВЬТЕ ПАЧКИ — жмите 🔄"
                    + (plan.hammerCellId > 0 ? (" или 🔨 #" + plan.hammerCellId) : " Перемешать"));
            lblMini.setTextColor(0xFFFCA5A5);
        } else if (plan != null && plan.partialBeforeReroll && !plan.steps.isEmpty()) {
            lblMini.setText("💡 ТОЛЬКО Шаг 1–" + plan.steps.size() + " (+" + plan.totalGainedScore
                    + ", пусто " + plan.finalEmptyCells + ") → ЗАТЕМ 🔄 и ✋ Рука!");
            lblMini.setTextColor(0xFFFDE047);
        } else if (plan != null && !plan.steps.isEmpty()
                && plan.hammerCellId > 0 && plan.finalEmptyCells <= 2) {
            lblMini.setText("⚠ Пусто " + plan.finalEmptyCells + "! Лучше 🔨 #" + plan.hammerCellId
                    + (plan.recommendReroll ? " или 🔄" : "")
                    + " · Итог " + plan.finalScore);
            lblMini.setTextColor(0xFFE9D5FF);
        } else if (plan != null && !plan.steps.isEmpty() && plan.recommendReroll) {
            lblMini.setText("⚠ Тупик (пусто " + plan.finalEmptyCells + ")! Совет: 🔄 Перемешать"
                    + (plan.hammerCellId > 0 ? (" или 🔨#" + plan.hammerCellId) : "")
                    + " · Итог " + plan.finalScore);
            lblMini.setTextColor(0xFFFDE047);
        } else if (plan != null && !plan.steps.isEmpty()) {
            lblMini.setText("⠿ Итог " + plan.finalScore + " (+" + plan.totalGainedScore
                    + ") · Пусто " + plan.finalEmptyCells + " · Сл.рука " + Math.round(plan.mergeProbNextHand) + "%");
        } else {
            lblMini.setText("⠿ Стопки руки: нажмите «✋ Рука» (или «⏩ Шаг+Рука»)");
        }
        LinearLayout.LayoutParams lmLp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        topBar.addView(lblMini, lmLp);

        String orderTxt = st.overlayStripSortByStep ? "Б: по шагам 1→3 ⇄" : "А: над стопкой ⇄";
        Button btnOrder = makeSmallBtn(
                orderTxt,
                st.overlayStripSortByStep ? 0xFF0C4A6E : 0xFF064E3B,
                st.overlayStripSortByStep ? 0xFF38BDF8 : 0xFF34D399,
                8.8f, 5, 2
        );
        btnOrder.setOnClickListener(v -> {
            st.overlayStripSortByStep = !st.overlayStripSortByStep;
            saveOverlayPrefs(st);
            refreshOverlayUi();
        });
        topBar.addView(btnOrder);
        if (attachDragToHeader && bottomStripContainer != null && bottomParams != null) {
            attachWindowDragListener(lblMini, bottomStripContainer, bottomParams, false, false);
        }
        host.addView(topBar);

        // Map slotId (1..3) -> assigned StepMove (if any)
        Map<Integer, SolverEngine.StepMove> slotMoveMap = new HashMap<>();
        if (plan != null && plan.steps != null) {
            for (SolverEngine.StepMove sm : plan.steps) {
                slotMoveMap.put(sm.slotId, sm);
            }
        }

        // Determine display order of the 3 slots:
        // Mode A (default): [1, 2, 3] strictly over Left, Middle, Right physical stacks!
        // Mode B: ordered by Step 1 -> Step 2 -> Step 3!
        int[] slotOrder = new int[]{1, 2, 3};
        if (st.overlayStripSortByStep && plan != null && plan.steps != null && !plan.steps.isEmpty()) {
            List<Integer> ord = new ArrayList<>(3);
            for (SolverEngine.StepMove sm : plan.steps) {
                if (sm.slotId >= 1 && sm.slotId <= 3 && !ord.contains(sm.slotId)) {
                    ord.add(sm.slotId);
                }
            }
            for (int sid = 1; sid <= 3; sid++) {
                if (!ord.contains(sid)) ord.add(sid);
            }
            for (int i = 0; i < 3; i++) slotOrder[i] = ord.get(i);
        }

        int rowW = Math.max(sdp(180), targetWidthPx - dp(10));
        LinearLayout cardsRow = new LinearLayout(this);
        cardsRow.setOrientation(LinearLayout.HORIZONTAL);
        cardsRow.setLayoutParams(new LinearLayout.LayoutParams(
                rowW, LinearLayout.LayoutParams.WRAP_CONTENT));

        int activeSlotsCount = 0;
        for (int sid = 1; sid <= 3; sid++) {
            SolverEngine.StepMove smChk = slotMoveMap.get(sid);
            List<SolverEngine.Layer> lChk = (smChk != null && smChk.cardLayers != null && !smChk.cardLayers.isEmpty())
                    ? smChk.cardLayers : st.waits.get(sid);
            if (lChk != null && !lChk.isEmpty()) activeSlotsCount++;
        }
        boolean centerCompactTray = (activeSlotsCount == 1 || activeSlotsCount == 2);
        if (centerCompactTray) {
            cardsRow.setGravity(Gravity.CENTER_HORIZONTAL);
        }
        int oneThirdCardW = Math.max(sdp(82), (rowW - 2 * sdp(3)) / 3);

        String[] shortSlotNames = {"", "ЛЕВ #1", "СР #2", "ПРАВ #3"};
        boolean fatalDeadlock = plan != null && (plan.deadlockStopAll || (plan.recommendReroll && plan.finalEmptyCells == 0));
        int renderedCards = 0;

        for (int idx = 0; idx < 3; idx++) {
            int sid = slotOrder[idx];
            SolverEngine.StepMove sm = slotMoveMap.get(sid);
            List<SolverEngine.Layer> layers = (sm != null && sm.cardLayers != null && !sm.cardLayers.isEmpty())
                    ? sm.cardLayers : st.waits.get(sid);
            boolean hasCardChips = layers != null && !layers.isEmpty();
            if (centerCompactTray && !hasCardChips) {
                continue;
            }

            int stepNo = (sm != null) ? sm.stepNum : 0;
            boolean holdForBooster = hasCardChips && sm == null && plan != null
                    && (plan.deadlockStopAll || plan.partialBeforeReroll);
            int cardBg, cardBd, badgeBg;
            if (holdForBooster || (fatalDeadlock && stepNo > 0)) {
                cardBg = 0xEE450A0A; cardBd = 0xFFF87171; badgeBg = 0xFFB91C1C;
            } else if (stepNo == 1) {
                cardBg = 0xEE063E2E; cardBd = 0xFF34D399; badgeBg = 0xFF059669;
            } else if (stepNo == 2) {
                cardBg = 0xEE0C2E47; cardBd = 0xFF38BDF8; badgeBg = 0xFF0284C7;
            } else if (stepNo == 3) {
                cardBg = 0xEE3B2506; cardBd = 0xFFF59E0B; badgeBg = 0xFFB45309;
            } else {
                cardBg = 0xEE1E293B; cardBd = 0xFF475569; badgeBg = 0xFF334155;
            }

            LinearLayout card = new LinearLayout(this);
            card.setOrientation(LinearLayout.VERTICAL);
            card.setPadding(sdp(4), sdp(3), sdp(4), sdp(3));
            card.setBackground(roundedBg(cardBg, cardBd, (stepNo == 1 || holdForBooster) ? 1.8f : 1.2f, 8f));
            LinearLayout.LayoutParams cLp = centerCompactTray
                    ? new LinearLayout.LayoutParams(oneThirdCardW, LinearLayout.LayoutParams.WRAP_CONTENT)
                    : new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
            if (renderedCards > 0) cLp.leftMargin = sdp(3);
            card.setLayoutParams(cLp);
            renderedCards++;

            // Card Line 1: [ШАГ N / ⛔ СТОП / ПУСТО] + ЛЕВ #1 / СР #2 / ПРАВ #3
            LinearLayout line1 = new LinearLayout(this);
            line1.setOrientation(LinearLayout.HORIZONTAL);
            line1.setGravity(Gravity.CENTER_VERTICAL);

            TextView stepBadge = new TextView(this);
            stepBadge.setText(stepNo > 0 ? ("ШАГ " + stepNo) : (holdForBooster ? "⛔ СТОП" : (hasCardChips ? "ЖДЁТ" : "ПУСТО")));
            stepBadge.setTextColor(Color.WHITE);
            setScaledTextDp(stepBadge, 8.9f);
            stepBadge.setTypeface(Typeface.DEFAULT_BOLD);
            stepBadge.setPadding(sdp(4), sdp(1), sdp(4), sdp(1));
            stepBadge.setBackground(roundedBg(badgeBg, 0xAAFFFFFF, 0.8f, 4f));
            line1.addView(stepBadge);

            TextView slotTv = new TextView(this);
            slotTv.setText(shortSlotNames[sid]);
            slotTv.setTextColor(0xFFE2E8F0);
            setScaledTextDp(slotTv, 8.7f);
            slotTv.setTypeface(Typeface.DEFAULT_BOLD);
            slotTv.setPadding(sdp(4), 0, 0, 0);
            slotTv.setSingleLine(true);
            line1.addView(slotTv);
            card.addView(line1);

            // Card Line 2: Top chip icon + "→ в #16 (+10)" or booster stop instruction
            LinearLayout line2 = new LinearLayout(this);
            line2.setOrientation(LinearLayout.HORIZONTAL);
            line2.setGravity(Gravity.CENTER_VERTICAL);
            LinearLayout.LayoutParams l2Lp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            l2Lp.topMargin = sdp(2);
            line2.setLayoutParams(l2Lp);

            if (hasCardChips) {
                int topCol = layers.get(layers.size() - 1).colour;
                ImageView topIc = new ImageView(this);
                topIc.setImageResource(getChipDrawableId(topCol));
                LinearLayout.LayoutParams icLp = new LinearLayout.LayoutParams(sdp(13), sdp(12));
                icLp.rightMargin = sdp(3);
                line2.addView(topIc, icLp);
            }

            TextView targetTv = new TextView(this);
            targetTv.setSingleLine(true);
            targetTv.setEllipsize(TextUtils.TruncateAt.END);
            targetTv.setTypeface(Typeface.DEFAULT_BOLD);
            setScaledTextDp(targetTv, 9.8f);
            if (sm != null) {
                String gain = sm.gainedScore > 0 ? (" (+" + sm.gainedScore + ")") : "";
                targetTv.setText("→ в #" + sm.targetCellId + gain);
                targetTv.setTextColor(fatalDeadlock ? 0xFFFCA5A5 : 0xFFFDE047);
            } else if (holdForBooster) {
                if (plan.partialBeforeReroll) {
                    targetTv.setText("после Ш" + plan.steps.size() + " → 🔄");
                } else {
                    targetTv.setText(plan.hammerCellId > 0 ? ("жми 🔄 / 🔨#" + plan.hammerCellId) : "жми 🔄 Перемеш.");
                }
                targetTv.setTextColor(0xFFFCA5A5);
            } else {
                targetTv.setText("нет хода");
                targetTv.setTextColor(0xFF94A3B8);
            }
            line2.addView(targetTv);
            card.addView(line2);

            // Card Line 3: Full Stack Layer Contents Shelf (icons + ×counts + text chain with ▲ top!)
            LinearLayout shelf = new LinearLayout(this);
            shelf.setOrientation(LinearLayout.VERTICAL);
            shelf.setPadding(sdp(3), sdp(2), sdp(3), sdp(2));
            shelf.setBackground(roundedBg(0xD8090E1A, 0xFF334155, 0.8f, 5f));
            LinearLayout.LayoutParams shLp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            shLp.topMargin = sdp(2);
            shelf.setLayoutParams(shLp);

            if (layers != null && !layers.isEmpty()) {
                HorizontalScrollView hsv = new HorizontalScrollView(this);
                hsv.setHorizontalScrollBarEnabled(false);
                LinearLayout pillsRow = new LinearLayout(this);
                pillsRow.setOrientation(LinearLayout.HORIZONTAL);
                pillsRow.setGravity(Gravity.CENTER_VERTICAL);

                for (int li = 0; li < layers.size(); li++) {
                    SolverEngine.Layer lay = layers.get(li);
                    boolean isTop = (li == layers.size() - 1);
                    if (li > 0) {
                        TextView arr = new TextView(this);
                        arr.setText("→");
                        arr.setTextColor(0xFF94A3B8);
                        setScaledTextDp(arr, 7.8f);
                        arr.setPadding(sdp(1), 0, sdp(1), 0);
                        pillsRow.addView(arr);
                    }
                    LinearLayout pill = new LinearLayout(this);
                    pill.setOrientation(LinearLayout.HORIZONTAL);
                    pill.setGravity(Gravity.CENTER_VERTICAL);
                    pill.setPadding(sdp(2.5f), sdp(1), sdp(3), sdp(1));
                    pill.setBackground(roundedBg(
                            getChipPillBg(lay.colour),
                            isTop ? 0xFFFFFFFF : getChipPillBorder(lay.colour),
                            isTop ? 1.2f : 0.8f, 4f));

                    ImageView lic = new ImageView(this);
                    lic.setImageResource(getChipDrawableId(lay.colour));
                    LinearLayout.LayoutParams llp = new LinearLayout.LayoutParams(sdp(10.5f), sdp(9.5f));
                    llp.rightMargin = sdp(2);
                    pill.addView(lic, llp);

                    TextView cntTv = new TextView(this);
                    cntTv.setText("×" + lay.count);
                    cntTv.setTextColor(Color.WHITE);
                    setScaledTextDp(cntTv, 8.2f);
                    cntTv.setTypeface(Typeface.DEFAULT_BOLD);
                    pill.addView(cntTv);
                    pillsRow.addView(pill);
                }
                hsv.addView(pillsRow);
                shelf.addView(hsv);

                TextView chainTv = new TextView(this);
                chainTv.setText(SolverEngine.formatLayersPlain(layers) + "▲");
                chainTv.setTextColor(0xFFCBD5E1);
                setScaledTextDp(chainTv, 7.5f);
                chainTv.setSingleLine(true);
                chainTv.setEllipsize(TextUtils.TruncateAt.MIDDLE);
                chainTv.setPadding(0, sdp(1), 0, 0);
                shelf.addView(chainTv);
            } else {
                TextView empTv = new TextView(this);
                empTv.setText("слот пуст");
                empTv.setTextColor(0xFF64748B);
                setScaledTextDp(empTv, 8.0f);
                empTv.setGravity(Gravity.CENTER);
                shelf.addView(empTv);
            }
            card.addView(shelf);
            cardsRow.addView(card);
        }

        host.addView(cardsRow);
    }

    /**
     * Builds the collapsible drawers inside Top Dock supporting ALL 3 open combinations cleanly:
     * - Only [Поле ▲] open
     * - Only [? Сверить ▲] open
     * - BOTH [Поле ▲] AND [? Сверить ▲] open side-by-side!
     */
    private void rebuildDrawersUi(final AppState st) {
        drawerContainer.removeAllViews();
        final SolverEngine.BoardDiffResult pdr = st.pendingDiffResult;
        boolean showBoard = isBoardDrawerOpen;
        boolean showDiff = (pdr != null);

        if (!showBoard && !showDiff) {
            drawerContainer.setVisibility(View.GONE);
            return;
        }
        drawerContainer.setVisibility(View.VISIBLE);

        if (showBoard && showDiff) {
            // Side-by-side 2-column layout (Panel 4 of approved mockup!)
            LinearLayout sideRow = new LinearLayout(this);
            sideRow.setOrientation(LinearLayout.HORIZONTAL);

            LinearLayout leftCol = buildDiffDrawerBox(st, pdr, true);
            LinearLayout.LayoutParams lLp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.06f);
            sideRow.addView(leftCol, lLp);

            LinearLayout rightCol = buildBoardDrawerBox(st, true);
            LinearLayout.LayoutParams rLp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 0.94f);
            rLp.leftMargin = dp(4);
            sideRow.addView(rightCol, rLp);

            drawerContainer.addView(sideRow);
        } else if (showBoard) {
            drawerContainer.addView(buildBoardDrawerBox(st, false));
        } else {
            drawerContainer.addView(buildDiffDrawerBox(st, pdr, false));
        }
    }

    private LinearLayout buildBoardDrawerBox(final AppState st, final boolean compactSideBySide) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(6), dp(5), dp(6), dp(6));
        box.setBackground(roundedBg(0xF40F172A, 0xFF60A5FA, 1.4f, 10f));

        TextView title = new TextView(this);
        title.setText(compactSideBySide
                ? "Поле решалки (тап = слои)"
                : "Основное поле решалки (Этап " + st.stageNo + " • тап по клетке = слои)");
        title.setTextColor(0xFFBFDBFE);
        setScaledTextDp(title, compactSideBySide ? 9.2f : 10.2f);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        box.addView(title);

        final TextView inspectorTv = new TextView(this);
        inspectorTv.setTextColor(0xFFE2E8F0);
        setScaledTextDp(inspectorTv, compactSideBySide ? 8.6f : 9.4f);

        MiniHexOverlayView hexView = new MiniHexOverlayView(this);
        hexView.setBackground(roundedBg(0xFF0B1120, 0xFF334155, 1.0f, 8f));

        // Pick default inspected cell if none chosen yet
        if (st.inspectedOverlayCellId <= 0 && st.cells != null) {
            for (int cid = 1; cid <= SolverEngine.MAX_POS_COUNT; cid++) {
                if (st.cells[cid] != null && st.cells[cid].exist == 1 && st.cells[cid].hasBlocks()) {
                    st.inspectedOverlayCellId = cid;
                    break;
                }
            }
        }
        updateCellInspectorText(st, inspectorTv, compactSideBySide);
        hexView.setOnCellTapListener(cid -> updateCellInspectorText(st, inspectorTv, compactSideBySide));

        if (compactSideBySide) {
            LinearLayout.LayoutParams hLp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, dp(126));
            hLp.topMargin = dp(4);
            box.addView(hexView, hLp);

            inspectorTv.setPadding(dp(4), dp(3), dp(4), dp(2));
            box.addView(inspectorTv);
        } else {
            LinearLayout midRow = new LinearLayout(this);
            midRow.setOrientation(LinearLayout.HORIZONTAL);
            LinearLayout.LayoutParams mrLp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            mrLp.topMargin = dp(4);
            midRow.setLayoutParams(mrLp);

            LinearLayout.LayoutParams hLp = new LinearLayout.LayoutParams(0, dp(126), 1.28f);
            midRow.addView(hexView, hLp);

            LinearLayout inspCard = new LinearLayout(this);
            inspCard.setOrientation(LinearLayout.VERTICAL);
            inspCard.setPadding(dp(6), dp(5), dp(6), dp(5));
            inspCard.setBackground(roundedBg(0xFF171F30, 0xFF475569, 1.0f, 8f));
            LinearLayout.LayoutParams icLp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 0.92f);
            icLp.leftMargin = dp(5);
            inspCard.setLayoutParams(icLp);

            inspCard.addView(inspectorTv);

            Button btnEditCell = makeSmallBtn("✎ Изм. клетку", 0xFF1E40AF, 0xFF93C5FD, 9.5f, 5, 3);
            LinearLayout.LayoutParams ecLp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            ecLp.topMargin = dp(5);
            btnEditCell.setOnClickListener(v -> openMainApp());
            inspCard.addView(btnEditCell, ecLp);

            midRow.addView(inspCard);
            box.addView(midRow);

            // Footer buttons: [↗ Править в полном поле] + [▲ Скрыть поле]
            LinearLayout foot = new LinearLayout(this);
            foot.setOrientation(LinearLayout.HORIZONTAL);
            LinearLayout.LayoutParams fLp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            fLp.topMargin = dp(5);
            foot.setLayoutParams(fLp);

            Button btnOpenFull = makeSmallBtn("↗ Править в полном поле", 0xFF1D4ED8, 0xFF93C5FD, 9.5f, 5, 3);
            Button btnHideBoard = makeSmallBtn("▲ Скрыть поле", 0xFF171F30, 0xFF94A3B8, 9.5f, 5, 3);

            LinearLayout.LayoutParams ofLp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.35f);
            LinearLayout.LayoutParams hbLp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 0.90f);
            hbLp.leftMargin = dp(4);

            btnOpenFull.setOnClickListener(v -> openMainApp());
            btnHideBoard.setOnClickListener(v -> {
                isBoardDrawerOpen = false;
                refreshOverlayUi();
            });

            foot.addView(btnOpenFull, ofLp);
            foot.addView(btnHideBoard, hbLp);
            box.addView(foot);
        }
        return box;
    }

    private void updateCellInspectorText(AppState st, TextView tv, boolean compact) {
        if (st.cells == null) {
            tv.setText("Поле пусто");
            return;
        }
        int emp = 0, locks = 0;
        for (int i = 1; i <= SolverEngine.MAX_POS_COUNT; i++) {
            SolverEngine.CellState c = st.cells[i];
            if (c == null || c.exist != 1) continue;
            if (c.isEmptyPlaceable()) emp++;
            if (c.isLocked()) locks++;
        }
        int cid = st.inspectedOverlayCellId;
        if (cid < 1 || cid > SolverEngine.MAX_POS_COUNT || st.cells[cid] == null || st.cells[cid].exist != 1) {
            tv.setText("Нажмите на клетку\nПусто: " + emp + " кл.");
            return;
        }
        SolverEngine.CellState cs = st.cells[cid];
        if (compact) {
            tv.setText("#" + cid + ": " + SolverEngine.formatLayersPlain(cs.layers) + " · Пусто:" + emp);
            return;
        }
        StringBuilder sb = new StringBuilder();
        sb.append("Слои по тапу:\n#").append(cid);
        if (cs.isLocked()) {
            sb.append(" (🔒 ").append(Math.max(cs.lockScore, cs.lockCardScore)).append(")");
        }
        if (!cs.hasBlocks()) {
            sb.append(": пусто\n");
        } else {
            int tot = 0;
            for (SolverEngine.Layer l : cs.layers) tot += l.count;
            sb.append(" (").append(tot).append(" шт.):\n");
            for (int i = cs.layers.size() - 1; i >= 0; i--) {
                SolverEngine.Layer l = cs.layers.get(i);
                String role = (i == cs.layers.size() - 1) ? "• верх " : (i == 0 ? "• низ  " : "• ср.  ");
                sb.append(role).append(SolverEngine.SHORT_NAMES_RU[l.colour]).append("×").append(l.count).append("\n");
            }
        }
        sb.append("Пусто: ").append(emp).append(" кл.");
        if (locks > 0) sb.append(" · Замков: ").append(locks);
        tv.setText(sb.toString());
    }

    private LinearLayout buildDiffDrawerBox(
            final AppState st,
            final SolverEngine.BoardDiffResult pdr,
            final boolean compactSideBySide
    ) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(sdp(6), sdp(5), sdp(6), sdp(6));
        boolean invalidFrame = pdr.invalidFrame;
        boolean cleanMatch = !invalidFrame && pdr.diffs.isEmpty();
        String turnIcon = (pdr.turnIconOnly != null) ? pdr.turnIconOnly.trim() : "";
        boolean hasTurnHint = !turnIcon.isEmpty();
        box.setBackground(roundedBg(
                invalidFrame ? 0xF2450A0A : (cleanMatch ? 0xF2063E2E : 0xF4171210),
                invalidFrame ? 0xFFF87171 : (cleanMatch ? 0xFF10B981 : 0xFFF59E0B),
                1.4f, 10f));

        if (invalidFrame) {
            TextView errMsg = new TextView(this);
            errMsg.setTextColor(0xFFFCA5A5);
            setScaledTextDp(errMsg, compactSideBySide ? 9.2f : 10.0f);
            errMsg.setTypeface(Typeface.DEFAULT_BOLD);
            errMsg.setText((pdr.occlusionGuidanceRu != null && !pdr.occlusionGuidanceRu.isEmpty())
                    ? pdr.occlusionGuidanceRu
                    : "⚠ Экран игры не виден — повторите скан");
            box.addView(errMsg);

            LinearLayout errBtns = new LinearLayout(this);
            errBtns.setOrientation(LinearLayout.HORIZONTAL);
            LinearLayout.LayoutParams ebLp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            ebLp.topMargin = sdp(5);
            errBtns.setLayoutParams(ebLp);
            Button btnClose = makeSmallBtn("✕", 0xFF334155, 0xFF94A3B8, 9.4f, 6, 4);
            btnClose.setOnClickListener(v -> {
                st.discardPendingDiffs();
                refreshOverlayUi();
                MainActivity.notifyNativeStateUpdated();
            });
            Button btnRetry = makeSmallBtn("↻ Повторить", 0xFFB91C1C, 0xFFFCA5A5, 9.4f, 6, 4);
            btnRetry.setOnClickListener(v -> triggerBoardDiffScan(false));
            LinearLayout.LayoutParams e1 = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            LinearLayout.LayoutParams e2 = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.0f);
            e2.leftMargin = sdp(4);
            errBtns.addView(btnClose, e1);
            errBtns.addView(btnRetry, e2);
            box.addView(errBtns);
            return box;
        }

        // Minimalist top status row: Causal Diagnosis on left + ICON-ONLY turn indicator on right (zero verbose occlusion text!)
        LinearLayout topRow = new LinearLayout(this);
        topRow.setOrientation(LinearLayout.HORIZONTAL);
        topRow.setGravity(Gravity.CENTER_VERTICAL);

        TextView diagTv = new TextView(this);
        String detBadge = (pdr.detectedTurnBadge != null && !pdr.detectedTurnBadge.isEmpty())
                ? (" · " + pdr.detectedTurnBadge)
                : ((pdr.scannedAngles != null && pdr.scannedAngles.size() > 1)
                        ? (" · ×" + pdr.scannedAngles.size()) : "");
        String diagText = (pdr.causalDiagnosisRu != null && !pdr.causalDiagnosisRu.isEmpty())
                ? pdr.causalDiagnosisRu
                : (cleanMatch ? "✅ Поле совпадает с игрой" : ("💡 Изменения: " + pdr.diffs.size() + " кл."));
        diagTv.setText(diagText + detBadge);
        diagTv.setTextColor(cleanMatch ? 0xFF6EE7B7 : 0xFFFDE047);
        setScaledTextDp(diagTv, compactSideBySide ? 9.1f : 9.9f);
        diagTv.setTypeface(Typeface.DEFAULT_BOLD);
        LinearLayout.LayoutParams dtLp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        topRow.addView(diagTv, dtLp);

        if (hasTurnHint) {
            Button btnTurnIcon = makeSmallBtn("🔄 " + turnIcon, 0xFF0369A1, 0xFFBAE6FD, 9.3f, 6, 3);
            btnTurnIcon.setOnClickListener(v -> triggerBoardDiffScan(true));
            LinearLayout.LayoutParams tiLp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            tiLp.leftMargin = sdp(4);
            topRow.addView(btnTurnIcon, tiLp);
        }
        box.addView(topRow);

        if (pdr.occlusionGuidanceRu != null && !pdr.occlusionGuidanceRu.isEmpty()) {
            TextView warnBanner = new TextView(this);
            warnBanner.setText(pdr.occlusionGuidanceRu);
            warnBanner.setTextColor(0xFFFEF08A);
            setScaledTextDp(warnBanner, 8.8f);
            LinearLayout.LayoutParams wbLp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            wbLp.topMargin = sdp(3);
            warnBanner.setLayoutParams(wbLp);
            box.addView(warnBanner);
        }

        if (cleanMatch) {
            LinearLayout okBtns = new LinearLayout(this);
            okBtns.setOrientation(LinearLayout.HORIZONTAL);
            LinearLayout.LayoutParams obLp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            obLp.topMargin = sdp(5);
            okBtns.setLayoutParams(obLp);

            Button btnDone = makeSmallBtn("✓ Ок (Счёт " + (pdr.detectedScore >= 0 ? pdr.detectedScore : st.curScore) + ")",
                    0xFF059669, 0xFF6EE7B7, 9.5f, 6, 4);
            btnDone.setOnClickListener(v -> {
                st.discardPendingDiffs();
                refreshOverlayUi();
                MainActivity.notifyNativeStateUpdated();
            });
            Button btnRescan = makeSmallBtn("↻", 0xFF0F2A44, 0xFF38BDF8, 9.5f, 6, 4);
            btnRescan.setOnClickListener(v -> triggerBoardDiffScan(false));

            LinearLayout.LayoutParams d1 = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
            LinearLayout.LayoutParams d2 = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            d2.leftMargin = sdp(4);
            okBtns.addView(btnDone, d1);
            okBtns.addView(btnRescan, d2);
            box.addView(okBtns);
            return box;
        }

        int selCount = 0;
        for (SolverEngine.CellDiff cd : pdr.diffs) if (cd.selected) selCount++;

        final int maxScrollDp = compactSideBySide ? 92 : 108;
        ScrollView diffScroll = new ScrollView(this) {
            @Override
            protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
                int maxH = sdp(maxScrollDp);
                int hMode = MeasureSpec.getMode(heightMeasureSpec);
                int hSize = MeasureSpec.getSize(heightMeasureSpec);
                int capped = (hMode == MeasureSpec.UNSPECIFIED)
                        ? MeasureSpec.makeMeasureSpec(maxH, MeasureSpec.AT_MOST)
                        : MeasureSpec.makeMeasureSpec(Math.min(hSize, maxH), hMode);
                super.onMeasure(widthMeasureSpec, capped);
            }
        };
        LinearLayout.LayoutParams scLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        scLp.topMargin = sdp(4);
        diffScroll.setLayoutParams(scLp);

        LinearLayout listInner = new LinearLayout(this);
        listInner.setOrientation(LinearLayout.VERTICAL);

        for (final SolverEngine.CellDiff d : pdr.diffs) {
            TextView rowTv = new TextView(this);
            rowTv.setTextColor(d.selected ? 0xFFFEF3C7 : 0xFF94A3B8);
            setScaledTextDp(rowTv, compactSideBySide ? 8.9f : 9.5f);
            rowTv.setTypeface(d.selected ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
            rowTv.setPadding(sdp(5), sdp(3), sdp(5), sdp(3));
            rowTv.setBackground(roundedBg(
                    d.selected ? 0xFF451A03 : 0xFF171F30,
                    d.selected ? 0xFFF59E0B : 0xFF475569,
                    1.0f, 5f));
            LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            rlp.bottomMargin = sdp(3);
            rowTv.setLayoutParams(rlp);

            String chk = d.selected ? "☑ " : "☐ ";
            String line = chk + "#" + d.cellId + ": "
                    + SolverEngine.formatLayersPlain(d.appLayers)
                    + " → " + SolverEngine.formatLayersPlain(d.gameLayers);
            rowTv.setText(line);
            rowTv.setOnClickListener(v -> {
                d.selected = !d.selected;
                st.inspectedOverlayCellId = d.cellId;
                refreshOverlayUi();
            });
            listInner.addView(rowTv);
        }
        diffScroll.addView(listInner);
        box.addView(diffScroll);

        LinearLayout confirmRow = new LinearLayout(this);
        confirmRow.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams crLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        crLp.topMargin = sdp(4);
        confirmRow.setLayoutParams(crLp);

        String applyLbl = compactSideBySide
                ? ("✓ Прим.(" + selCount + ")")
                : ("✓ Привести к игре (" + selCount + ")");
        Button btnApply = makeSmallBtn(applyLbl, 0xFF059669, 0xFF6EE7B7, compactSideBySide ? 8.9f : 9.5f, 6, 4);
        Button btnRescan = makeSmallBtn("↻", 0xFF0F2A44, 0xFF38BDF8, 9.5f, 6, 4);
        Button btnEditInApp = makeSmallBtn("↗", 0xFF1D4ED8, 0xFF93C5FD, 9.5f, 6, 4);
        Button btnCancel = makeSmallBtn("✕", 0xFF7F1D1D, 0xFFFCA5A5, 9.5f, 6, 4);

        LinearLayout.LayoutParams apLp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.0f);
        LinearLayout.LayoutParams rsLp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        rsLp.leftMargin = sdp(3);
        LinearLayout.LayoutParams edLp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        edLp.leftMargin = sdp(3);
        LinearLayout.LayoutParams cnLp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        cnLp.leftMargin = sdp(3);

        btnApply.setOnClickListener(v -> {
            int n = st.applySelectedPendingDiffs(getApplicationContext());
            Toast.makeText(this, "✓ Поле приведено к игре (" + n + " кл.)", Toast.LENGTH_SHORT).show();
            refreshOverlayUi();
            MainActivity.notifyNativeStateUpdated();
        });
        btnRescan.setOnClickListener(v -> triggerBoardDiffScan(false));
        btnEditInApp.setOnClickListener(v -> openMainApp());
        btnCancel.setOnClickListener(v -> {
            st.discardPendingDiffs();
            refreshOverlayUi();
            MainActivity.notifyNativeStateUpdated();
        });

        confirmRow.addView(btnApply, apLp);
        confirmRow.addView(btnRescan, rsLp);
        confirmRow.addView(btnEditInApp, edLp);
        confirmRow.addView(btnCancel, cnLp);
        box.addView(confirmRow);
        return box;
    }

    /**
     * 100% Touch-Through AR Canvas View (FLAG_NOT_TOUCHABLE) drawing:
     * 1) Glowing target circles 1, 2, 3 right inside the real game's target hex cells;
     * 2) Amber hex outlines ! #cid right over differing cells during "? Сверить".
     */
    private final class ArTargetsCanvasView extends View {
        int desiredVisibility = View.VISIBLE;
        private final Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint strokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path hexPath = new Path();
        private final RectF pillRect = new RectF();
        private final int[] winLoc = new int[2];

        ArTargetsCanvasView(Context ctx) {
            super(ctx);
            fillPaint.setStyle(Paint.Style.FILL);
            strokePaint.setStyle(Paint.Style.STROKE);
            textPaint.setTextAlign(Paint.Align.CENTER);
            textPaint.setFakeBoldText(true);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            if (isCollapsed) return;
            AppState st = AppState.get();
            if (st.pendingDiffResult != null) return;
            SolverEngine.ScreenBoardGeometry geo = st.screenGeo;
            if (geo == null) return;

            getLocationOnScreen(winLoc);
            float offX = winLoc[0];
            float offY = winLoc[1];

            // 1. Draw AR Target Circles 1-2-3 when AR is active (Combo mode 0 or AR-Only mode 1)
            boolean drawSteps = (st.overlayDisplayMode == 0 || st.overlayDisplayMode == 1);
            SolverEngine.SolvePlan plan = st.lastPlan;
            Map<Integer, String> cellStepLbl = new LinkedHashMap<>();
            if (drawSteps && plan != null && plan.steps != null && !plan.steps.isEmpty()) {
                boolean fatal = !plan.partialBeforeReroll && plan.recommendReroll && plan.finalEmptyCells == 0;
                // Group steps by targetCellId in case two moves land on the same cell
                Map<Integer, Integer> cellFirstStep = new HashMap<>();
                Map<Integer, Integer> cellGain = new HashMap<>();
                for (SolverEngine.StepMove sm : plan.steps) {
                    int cid = sm.targetCellId;
                    String prev = cellStepLbl.get(cid);
                    cellStepLbl.put(cid, prev == null ? String.valueOf(sm.stepNum) : (prev + "·" + sm.stepNum));
                    if (!cellFirstStep.containsKey(cid)) cellFirstStep.put(cid, sm.stepNum);
                    cellGain.put(cid, (cellGain.containsKey(cid) ? cellGain.get(cid) : 0) + sm.gainedScore);
                }

                for (Map.Entry<Integer, String> e : cellStepLbl.entrySet()) {
                    int cid = e.getKey();
                    String stepStr = e.getValue();
                    int firstStep = cellFirstStep.get(cid);
                    int totGain = cellGain.get(cid);

                    float[] pt = geo.getCellScreenCenter(cid, realScreenW, realScreenH);
                    float cx = pt[0] - offX;
                    float cy = pt[1] - offY;
                    float outerR = Math.max(dp(17), Math.min(dp(26), pt[2] * 0.68f));
                    float innerR = outerR * 0.58f;

                    int accent = fatal ? 0xFFF87171
                            : (firstStep == 1 ? 0xFF34D399 : (firstStep == 2 ? 0xFF38BDF8 : 0xFFF59E0B));
                    int halo = (accent & 0x00FFFFFF) | 0x38000000;

                    // Outer translucent target ring
                    fillPaint.setColor(halo);
                    canvas.drawCircle(cx, cy, outerR, fillPaint);
                    strokePaint.setColor(accent);
                    strokePaint.setStrokeWidth(dp(2.5f));
                    canvas.drawCircle(cx, cy, outerR, strokePaint);

                    // Crisp inner badge with bold step number
                    fillPaint.setColor(0xE60F172A);
                    canvas.drawCircle(cx, cy, innerR, fillPaint);
                    strokePaint.setColor(0xEEFFFFFF);
                    strokePaint.setStrokeWidth(dp(1.4f));
                    canvas.drawCircle(cx, cy, innerR, strokePaint);

                    textPaint.setColor(Color.WHITE);
                    textPaint.setTextSize(stepStr.length() > 1 ? innerR * 0.92f : innerR * 1.22f);
                    canvas.drawText(stepStr, cx, cy + innerR * 0.40f, textPaint);

                    // Compact "#16 +10" tag right under circle (with "после Ш1" when cell currently has a stack/lock!)
                    boolean currentlyOccupiedOrLocked = st.cells != null && cid >= 1 && cid < st.cells.length
                            && st.cells[cid] != null && !st.cells[cid].isEmptyPlaceable();
                    String afterHint = (firstStep > 1 && currentlyOccupiedOrLocked) ? ("·после Ш" + (firstStep - 1)) : "";
                    boolean alsoHammerTarget = (plan.hammerCellId == cid) && (plan.finalEmptyCells <= 2);
                    String tag = "#" + cid + afterHint + (totGain > 0 ? (" +" + totGain) : "") + (alsoHammerTarget ? " 🔨?" : "");
                    textPaint.setTextSize(Math.max(dp(9.2f), outerR * 0.44f));
                    float tw = textPaint.measureText(tag) + dp(9);
                    float th = Math.max(dp(14), outerR * 0.64f);
                    pillRect.set(cx - tw * 0.5f, cy + innerR + dp(2), cx + tw * 0.5f, cy + innerR + dp(2) + th);
                    fillPaint.setColor(0xE60F172A);
                    canvas.drawRoundRect(pillRect, dp(5), dp(5), fillPaint);
                    strokePaint.setColor(accent);
                    strokePaint.setStrokeWidth(dp(1.1f));
                    canvas.drawRoundRect(pillRect, dp(5), dp(5), strokePaint);
                    textPaint.setColor(0xFFFEF9C3);
                    canvas.drawText(tag, cx, pillRect.centerY() + th * 0.28f, textPaint);
                }
            }

            // 1b. Draw Violet-Gold Hammer Target Badge (🔨#cid) when board is critical / deadlocked (even when steps is empty!)
            if (drawSteps && plan != null && plan.hammerCellId > 0
                    && (plan.deadlockStopAll || plan.finalEmptyCells <= 1 || plan.recommendReroll)
                    && !cellStepLbl.containsKey(plan.hammerCellId)) {
                int hCid = plan.hammerCellId;
                float[] pt = geo.getCellScreenCenter(hCid, realScreenW, realScreenH);
                float cx = pt[0] - offX;
                float cy = pt[1] - offY;
                float outerR = Math.max(dp(16), Math.min(dp(24), pt[2] * 0.64f));
                float innerR = outerR * 0.56f;
                int hAccent = 0xFFC084FC;

                fillPaint.setColor(0x40A855F7);
                canvas.drawCircle(cx, cy, outerR, fillPaint);
                strokePaint.setColor(hAccent);
                strokePaint.setStrokeWidth(dp(2.3f));
                canvas.drawCircle(cx, cy, outerR, strokePaint);

                fillPaint.setColor(0xEB2E1065);
                canvas.drawCircle(cx, cy, innerR, fillPaint);
                textPaint.setColor(Color.WHITE);
                textPaint.setTextSize(innerR * 1.05f);
                canvas.drawText("🔨", cx, cy + innerR * 0.36f, textPaint);

                String hTag = "🔨 #" + hCid;
                textPaint.setTextSize(Math.max(dp(9.0f), outerR * 0.44f));
                float tw = textPaint.measureText(hTag) + dp(8);
                float th = Math.max(dp(14), outerR * 0.62f);
                pillRect.set(cx - tw * 0.5f, cy + innerR + dp(2), cx + tw * 0.5f, cy + innerR + dp(2) + th);
                fillPaint.setColor(0xEE2E1065);
                canvas.drawRoundRect(pillRect, dp(5), dp(5), fillPaint);
                strokePaint.setColor(hAccent);
                strokePaint.setStrokeWidth(dp(1.1f));
                canvas.drawRoundRect(pillRect, dp(5), dp(5), strokePaint);
                textPaint.setColor(0xFFF3E8FF);
                canvas.drawText(hTag, cx, pillRect.centerY() + th * 0.28f, textPaint);
            }
        }
    }

    private void releaseCaptureResources() {
        if (virtualDisplay != null) {
            try { virtualDisplay.release(); } catch (Exception ignored) {}
            virtualDisplay = null;
        }
        if (imageReader != null) {
            try { imageReader.close(); } catch (Exception ignored) {}
            imageReader = null;
        }
        if (mediaProjection != null) {
            try { mediaProjection.unregisterCallback(projectionCallback); } catch (Exception ignored) {}
            try { mediaProjection.stop(); } catch (Exception ignored) {}
            mediaProjection = null;
        }
    }

    @Override
    public void onDestroy() {
        if (sActiveService == this) {
            sActiveService = null;
        }
        releaseCaptureResources();
        if (windowManager != null) {
            if (arTargetsView != null) {
                try { windowManager.removeView(arTargetsView); } catch (Exception ignored) {}
                arTargetsView = null;
            }
            if (bottomStripContainer != null) {
                try { windowManager.removeView(bottomStripContainer); } catch (Exception ignored) {}
                bottomStripContainer = null;
            }
            if (rootContainer != null) {
                try { windowManager.removeView(rootContainer); } catch (Exception ignored) {}
                rootContainer = null;
            }
        }
        worker.shutdownNow();
        super.onDestroy();
    }
}
