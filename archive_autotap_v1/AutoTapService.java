package ru.doublejoy.solver;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.content.Context;
import android.content.Intent;
import android.graphics.Path;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.accessibility.AccessibilityEvent;

/**
 * Автотап — lets the overlay autopilot press and drag stacks inside the game using
 * Android's standard accessibility gesture injection (no root). The user must enable
 * this service once in Settings → Accessibility.
 *
 * Safety guard: gestures are dispatched ONLY while the foreground window belongs to
 * the game (com.phs.global). Events of our own overlay package never update the guard,
 * so the visible overlay cannot mask another app being in front.
 */
public final class AutoTapService extends AccessibilityService {
    private static final String GAME_PKG = "com.phs.global";
    private static final String OWN_PKG = "ru.doublejoy.solver";

    private static volatile AutoTapService instance;
    private static volatile String activePackage = "";
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    public static boolean isServiceEnabled() {
        return instance != null;
    }

    public static String getActivePackage() {
        return activePackage;
    }

    /** Opens the system Accessibility settings page (where this service must be switched on). */
    public static boolean openAccessibilitySettings(Context ctx) {
        try {
            ctx.startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Press at (ax,ay), hold holdMs (захват стопки), drag to (bx,by), release.
     * Callbacks fire on the main thread. Falls back to a simple one-stroke drag if the
     * press+hold continuation is rejected by the device.
     */
    public static void performDrag(final float ax, final float ay, final float bx, final float by,
                                   final long holdMs, final long moveMs,
                                   final Runnable onDone, final Runnable onFail) {
        final AutoTapService svc = instance;
        if (svc == null) { fail(onFail); return; }
        if (!GAME_PKG.equals(activePackage)) { fail(onFail); return; }
        MAIN.post(new Runnable() {
            @Override public void run() {
                AutoTapService s = instance;
                if (s == null) { fail(onFail); return; }
                try {
                    Path press = new Path();
                    press.moveTo(ax, ay);
                    GestureDescription gesture;
                    if (holdMs > 0) {
                        GestureDescription.StrokeDescription s1 =
                                new GestureDescription.StrokeDescription(press, 0, holdMs, true);
                        Path move = new Path();
                        move.moveTo(ax, ay);
                        move.lineTo(bx, by);
                        gesture = new GestureDescription.Builder()
                                .addStroke(s1.continueStroke(move, holdMs, Math.max(80, moveMs), false))
                                .build();
                    } else {
                        press.lineTo(bx, by);
                        gesture = new GestureDescription.Builder()
                                .addStroke(new GestureDescription.StrokeDescription(press, 0, Math.max(120, moveMs), false))
                                .build();
                    }
                    boolean dispatched = s.dispatchGesture(gesture, callback(onDone, onFail), null);
                    if (!dispatched) fail(onFail);
                } catch (Throwable t) {
                    // Fallback: один непрерывный жест без фазы удержания
                    try {
                        Path p = new Path();
                        p.moveTo(ax, ay);
                        p.lineTo(bx, by);
                        GestureDescription g2 = new GestureDescription.Builder()
                                .addStroke(new GestureDescription.StrokeDescription(p, 0, Math.max(200, moveMs), false))
                                .build();
                        boolean ok2 = s.dispatchGesture(g2, callback(onDone, onFail), null);
                        if (!ok2) fail(onFail);
                    } catch (Throwable t2) {
                        fail(onFail);
                    }
                }
            }
        });
    }

    private static GestureResultCallback callback(final Runnable onDone, final Runnable onFail) {
        return new GestureResultCallback() {
            @Override public void onCompleted(GestureDescription gesture) {
                if (onDone != null) MAIN.post(onDone);
            }
            @Override public void onCancelled(GestureDescription gesture) {
                if (onFail != null) MAIN.post(onFail);
            }
        };
    }

    private static void fail(final Runnable onFail) {
        if (onFail == null) return;
        MAIN.post(onFail);
    }

    @Override
    public void onServiceConnected() {
        super.onServiceConnected();
        instance = this;
    }

    @Override
    public void onDestroy() {
        if (instance == this) instance = null;
        super.onDestroy();
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event == null) return;
        int t = event.getEventType();
        if (t != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
                && t != AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) return;
        CharSequence pkg = event.getPackageName();
        if (pkg == null) return;
        String p = pkg.toString();
        if (OWN_PKG.equals(p)) return; // наш оверлей не должен перехватывать «передний план»
        activePackage = p;
    }

    @Override
    public void onInterrupt() {
    }
}
