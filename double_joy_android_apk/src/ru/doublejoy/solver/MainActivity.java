package ru.doublejoy.solver;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.projection.MediaProjectionManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.provider.Settings;
import android.util.Base64;
import android.webkit.JavascriptInterface;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import org.json.JSONObject;

import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.Map;

/**
 * Main full-screen activity hosting the rich offline HTML5 solver (file:///android_asset/solver.html)
 * and launching the MediaProjection + Floating Overlay service over Last Asylum: Plague.
 */
public final class MainActivity extends Activity {

    private static final int REQ_OVERLAY_PERM = 201;
    private static final int REQ_MEDIA_PROJECTION = 202;
    private static final int REQ_FILE_CHOOSER = 203;
    private static final int REQ_POST_NOTIF = 204;
    private static final int REQ_SAVE_DOCUMENT = 205;

    private static volatile MainActivity sActiveInstance;

    public static void notifyNativeStateUpdated() {
        final MainActivity act = sActiveInstance;
        if (act != null) {
            act.runOnUiThread(() -> {
                if (act.pageLoaded && act.isActivityResumed) {
                    act.pushPendingNativeStateToWeb();
                }
            });
        }
    }

    private WebView webView;
    private ValueCallback<Uri[]> filePathCallback;
    private boolean pageLoaded = false;
    private boolean isActivityResumed = false;
    private byte[] pendingSaveBytes;
    private String pendingSaveName;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        sActiveInstance = this;
        AppState.get().ensureInitialized(getApplicationContext());

        webView = new WebView(this);
        WebSettings ws = webView.getSettings();
        ws.setJavaScriptEnabled(true);
        ws.setDomStorageEnabled(true);
        ws.setAllowFileAccess(true);
        ws.setAllowContentAccess(true);
        ws.setTextZoom(100);
        ws.setUseWideViewPort(true);
        ws.setLoadWithOverviewMode(true);
        ws.setMediaPlaybackRequiresUserGesture(false);
        ws.setCacheMode(WebSettings.LOAD_NO_CACHE);
        webView.clearCache(true);

        webView.addJavascriptInterface(new AndroidHostBridge(), "AndroidHost");

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView view, String url) {
                pageLoaded = true;
                if (AppState.get().hasPendingNativeUpdate || CaptureOverlayService.isRunning()) {
                    AppState.get().hasPendingNativeUpdate = true;
                    pushPendingNativeStateToWeb();
                } else {
                    view.evaluateJavascript(
                            "window.NativeBridge && window.NativeBridge.requestSync();",
                            null
                    );
                }
            }
        });

        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onShowFileChooser(
                    WebView wv, ValueCallback<Uri[]> callback, FileChooserParams params
            ) {
                if (filePathCallback != null) {
                    filePathCallback.onReceiveValue(null);
                }
                filePathCallback = callback;
                try {
                    Intent intent = params.createIntent();
                    startActivityForResult(intent, REQ_FILE_CHOOSER);
                } catch (Exception e) {
                    filePathCallback = null;
                    return false;
                }
                return true;
            }
        });

        setContentView(webView);
        webView.loadUrl("file:///android_asset/solver.html");
    }

    @Override
    protected void onResume() {
        super.onResume();
        sActiveInstance = this;
        isActivityResumed = true;
        if (webView != null) {
            webView.onResume();
            webView.resumeTimers();
            if (pageLoaded) {
                AppState st = AppState.get();
                if (st.hasPendingNativeUpdate || !st.pendingLogQueue.isEmpty()) {
                    st.hasPendingNativeUpdate = true;
                    pushPendingNativeStateToWeb();
                }
            }
        }
    }

    @Override
    protected void onPause() {
        isActivityResumed = false;
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        if (sActiveInstance == this) {
            sActiveInstance = null;
        }
        super.onDestroy();
    }

    private void pushPendingNativeStateToWeb() {
        AppState st = AppState.get();
        if ((!st.hasPendingNativeUpdate && st.pendingLogQueue.isEmpty()) || webView == null) return;
        st.hasPendingNativeUpdate = false;
        JSONObject payload = st.buildWebSyncPayload();
        final String jsArg = JSONObject.quote(payload.toString());
        webView.evaluateJavascript(
                "window.NativeBridge && window.NativeBridge.receiveNativeSnapshot(" + jsArg + ");",
                null
        );
    }

    private void requestOverlayAndStartProjection() {
        if (!Settings.canDrawOverlays(this)) {
            new AlertDialog.Builder(this)
                    .setTitle("Разрешение «Поверх других окон»")
                    .setMessage("Чтобы кнопка захвата и подсказка ходов отображались прямо поверх игры «Двойная радость», включите переключатель на следующем экране и вернитесь назад.")
                    .setPositiveButton("Открыть настройки", (d, w) -> {
                        Intent intent = new Intent(
                                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                Uri.parse("package:" + getPackageName())
                        );
                        startActivityForResult(intent, REQ_OVERLAY_PERM);
                    })
                    .setNegativeButton("Отмена", null)
                    .show();
            return;
        }

        if (Build.VERSION.SDK_INT >= 33) {
            if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQ_POST_NOTIF);
                return;
            }
        }

        launchMediaProjectionDialog();
    }

    private void launchMediaProjectionDialog() {
        MediaProjectionManager mpm = (MediaProjectionManager) getSystemService(Context.MEDIA_PROJECTION_SERVICE);
        if (mpm == null) {
            Toast.makeText(this, "MediaProjection недоступен на устройстве", Toast.LENGTH_LONG).show();
            return;
        }
        startActivityForResult(mpm.createScreenCaptureIntent(), REQ_MEDIA_PROJECTION);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_POST_NOTIF) {
            launchMediaProjectionDialog();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_OVERLAY_PERM) {
            if (Settings.canDrawOverlays(this)) {
                requestOverlayAndStartProjection();
            } else {
                Toast.makeText(this, "Разрешение поверх окон ещё не включено", Toast.LENGTH_SHORT).show();
            }
        } else if (requestCode == REQ_MEDIA_PROJECTION) {
            if (resultCode == RESULT_OK && data != null) {
                Intent svc = new Intent(this, CaptureOverlayService.class);
                svc.putExtra(CaptureOverlayService.EXTRA_RESULT_CODE, resultCode);
                svc.putExtra(CaptureOverlayService.EXTRA_RESULT_DATA, data);
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    startForegroundService(svc);
                } else {
                    startService(svc);
                }
                Toast.makeText(this, "✓ Плавающая панель запущена! Откройте игру «Двойная радость»", Toast.LENGTH_LONG).show();
                moveTaskToBack(true);
            } else {
                Toast.makeText(this, "Захват экрана отменён", Toast.LENGTH_SHORT).show();
            }
        } else if (requestCode == REQ_FILE_CHOOSER) {
            if (filePathCallback != null) {
                Uri[] results = WebChromeClient.FileChooserParams.parseResult(resultCode, data);
                filePathCallback.onReceiveValue(results);
                filePathCallback = null;
            }
        } else if (requestCode == REQ_SAVE_DOCUMENT) {
            if (resultCode == RESULT_OK && data != null && data.getData() != null && pendingSaveBytes != null) {
                Uri destUri = data.getData();
                try (OutputStream os = getContentResolver().openOutputStream(destUri, "w")) {
                    if (os != null) {
                        os.write(pendingSaveBytes);
                        os.flush();
                        Toast.makeText(this, "✓ Файл сохранён: " + (pendingSaveName != null ? pendingSaveName : ""), Toast.LENGTH_LONG).show();
                    }
                } catch (Exception e) {
                    Toast.makeText(this, "Ошибка записи файла: " + e.getMessage(), Toast.LENGTH_LONG).show();
                }
            }
            pendingSaveBytes = null;
        }
    }

    private void launchCreateDocumentPicker(String fileName, String mimeType, byte[] dataBytes) {
        pendingSaveName = fileName;
        pendingSaveBytes = dataBytes;
        try {
            Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            intent.setType(mimeType != null ? mimeType : "text/plain");
            intent.putExtra(Intent.EXTRA_TITLE, fileName);
            startActivityForResult(intent, REQ_SAVE_DOCUMENT);
        } catch (Exception e) {
            Toast.makeText(this, "Не удалось открыть выбор папки: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private void shareTextContent(String fileName, String content, String mimeType) {
        try {
            Intent send = new Intent(Intent.ACTION_SEND);
            send.setType(mimeType != null ? mimeType : "text/plain");
            send.putExtra(Intent.EXTRA_TITLE, fileName);
            send.putExtra(Intent.EXTRA_SUBJECT, fileName);
            send.putExtra(Intent.EXTRA_TEXT, content);
            startActivity(Intent.createChooser(send, "Поделиться: " + fileName));
        } catch (Exception e) {
            Toast.makeText(this, "Не удалось открыть меню отправки", Toast.LENGTH_SHORT).show();
        }
    }

    private void shareBinaryUri(String fileName, Uri fileUri, String mimeType) {
        try {
            Intent send = new Intent(Intent.ACTION_SEND);
            send.setType(mimeType != null ? mimeType : "application/zip");
            send.putExtra(Intent.EXTRA_TITLE, fileName);
            send.putExtra(Intent.EXTRA_SUBJECT, fileName);
            send.putExtra(Intent.EXTRA_STREAM, fileUri);
            send.setClipData(ClipData.newRawUri(fileName, fileUri));
            send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(Intent.createChooser(send, "Отправить файл: " + fileName));
        } catch (Exception e) {
            Toast.makeText(this, "Не удалось открыть меню отправки файла", Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    public void onBackPressed() {
        if (webView != null && pageLoaded) {
            webView.evaluateJavascript(
                    "(function(){ return (window.closeTopSheetIfAny && window.closeTopSheetIfAny()) ? '1' : '0'; })()",
                    val -> {
                        if ("\"1\"".equals(val)) {
                            return;
                        }
                        if (webView.canGoBack()) {
                            webView.goBack();
                        } else {
                            MainActivity.super.onBackPressed();
                        }
                    }
            );
            return;
        }
        super.onBackPressed();
    }

    public final class AndroidHostBridge {
        @JavascriptInterface
        public void startCaptureOverlay() {
            runOnUiThread(MainActivity.this::requestOverlayAndStartProjection);
        }

        @JavascriptInterface
        public boolean isOverlayActive() {
            return CaptureOverlayService.isRunning();
        }

        @JavascriptInterface
        public void switchToGame() {
            runOnUiThread(() -> moveTaskToBack(true));
        }

        @JavascriptInterface
        public void onWebStateChanged(String jsonStr) {
            AppState.get().updateFromWebJson(getApplicationContext(), jsonStr);
            CaptureOverlayService.notifyWebStateUpdated();
        }

        @JavascriptInterface
        public void clearPendingDiffs() {
            AppState.get().discardPendingDiffs();
            AppState.get().hasPendingNativeUpdate = false;
            CaptureOverlayService.notifyWebStateUpdated();
        }

        @JavascriptInterface
        public String solveHandJson(String jsonStr) {
            return AppState.get().solveWebJson(getApplicationContext(), jsonStr);
        }

        @JavascriptInterface
        public String scanScreenshotDiffJson(String dataUri) {
            return runScreenshotDiffInternal(dataUri, false);
        }

        @JavascriptInterface
        public String mergeScreenshotDiffJson(String dataUri) {
            return runScreenshotDiffInternal(dataUri, true);
        }

        private String runScreenshotDiffInternal(String dataUri, boolean mergePrevAngle) {
            try {
                if (dataUri == null || dataUri.isEmpty()) {
                    return "{\"hasPending\":false,\"error\":\"Пустое изображение\"}";
                }
                int comma = dataUri.indexOf(',');
                String b64 = comma >= 0 ? dataUri.substring(comma + 1) : dataUri;
                byte[] raw = Base64.decode(b64, Base64.DEFAULT);
                Bitmap bmp = BitmapFactory.decodeByteArray(raw, 0, raw.length);
                if (bmp == null) {
                    return "{\"hasPending\":false,\"error\":\"Не удалось декодировать скриншот\"}";
                }
                String screenId = LogScreenStore.saveScreenBitmap(
                        getApplicationContext(), bmp, mergePrevAngle ? "ДОСКАН_СКРИН" : "СВЕРКА_СКРИН"
                );
                AppState st = AppState.get();
                st.ensureInitialized(getApplicationContext());
                Map<Integer, SolverEngine.StageCfg> stages = SolverEngine.getStages(getApplicationContext());
                SolverEngine.BoardDiffResult res;
                synchronized (st) {
                    SolverEngine.BoardDiffResult prev = mergePrevAngle ? st.pendingDiffResult : null;
                    res = SolverEngine.computeOrMergeBoardDiffFromBitmap(
                            bmp, st.cells, st.stageNo, st.curScore, st.rotationDeg,
                            st.userCalibration, st.customPalette, stages, prev,
                            st.waits, st.lastPlan, st.causalHistory
                    );
                    res.lastScreenId = screenId != null ? screenId : "";
                    st.pendingDiffResult = res;
                    // Save current frame as prev frame for frame-to-frame delta filter
                    if (bmp != null && !bmp.isRecycled()) {
                        int w = bmp.getWidth();
                        int h = bmp.getHeight();
                        int[] argb = new int[w * h];
                        bmp.getPixels(argb, 0, w, 0, 0, w, h);
                        st.prevFrameArgb = argb;
                        st.prevFrameW = w;
                        st.prevFrameH = h;
                        st.hasPrevFrame = true;
                    }
                }
                bmp.recycle();
                CaptureOverlayService.notifyWebStateUpdated();
                return SolverEngine.boardDiffToJson(res).toString();
            } catch (Exception e) {
                return "{\"hasPending\":false,\"error\":\"" + String.valueOf(e.getMessage()).replace("\"", "'") + "\"}";
            }
        }

        @JavascriptInterface
        public String saveWebScreenDataUri(String dataUri, String tagHint) {
            try {
                if (dataUri == null || dataUri.isEmpty()) return "";
                int comma = dataUri.indexOf(',');
                String b64 = comma >= 0 ? dataUri.substring(comma + 1) : dataUri;
                byte[] raw = Base64.decode(b64, Base64.DEFAULT);
                Bitmap bmp = BitmapFactory.decodeByteArray(raw, 0, raw.length);
                if (bmp == null) return "";
                String sid = LogScreenStore.saveScreenBitmap(getApplicationContext(), bmp, tagHint != null ? tagHint : "СКРИН");
                bmp.recycle();
                return sid != null ? sid : "";
            } catch (Exception e) {
                return "";
            }
        }

        @JavascriptInterface
        public String getLogScreenDataUri(String screenId) {
            return LogScreenStore.readScreenDataUri(getApplicationContext(), screenId);
        }

        @JavascriptInterface
        public void clearLogScreens() {
            LogScreenStore.clearAllScreens(getApplicationContext());
        }

        @JavascriptInterface
        public boolean exportLogHtmlBundle(String rawBaseName, String payloadJsonStr) {
            try {
                final String safeJson = payloadJsonStr != null ? payloadJsonStr : "{}";
                final String safeBase = (rawBaseName != null && !rawBaseName.isEmpty())
                        ? rawBaseName : "Лог_Скрины_Двойная_радость";
                new Thread(() -> {
                    try {
                        LogScreenStore.HtmlBundleResult res = LogScreenStore.buildHtmlBundle(getApplicationContext(), safeJson);
                        final byte[] htmlBytes = res.htmlBytes;
                        String ts = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
                        String cleanBase = safeBase.replaceAll("\\.(html|zip|log|txt)$", "");
                        final String uniqueName = cleanBase + "_" + ts + ".html";

                        Uri savedMediaUri = null;
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                            String[] tryMimes = new String[]{"text/html", "application/octet-stream"};
                            for (String tryMime : tryMimes) {
                                try {
                                    ContentValues cv = new ContentValues();
                                    cv.put(MediaStore.Downloads.DISPLAY_NAME, uniqueName);
                                    cv.put(MediaStore.Downloads.MIME_TYPE, tryMime);
                                    cv.put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS);
                                    cv.put(MediaStore.Downloads.IS_PENDING, 1);
                                    Uri uri = getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv);
                                    if (uri != null) {
                                        try (OutputStream os = getContentResolver().openOutputStream(uri)) {
                                            if (os == null) throw new java.io.IOException("OutputStream is null");
                                            os.write(htmlBytes);
                                            os.flush();
                                        }
                                        ContentValues doneCv = new ContentValues();
                                        doneCv.put(MediaStore.Downloads.IS_PENDING, 0);
                                        getContentResolver().update(uri, doneCv, null, null);
                                        savedMediaUri = uri;
                                        break;
                                    }
                                } catch (Exception ignored) {}
                            }
                        }

                        final Uri finalUri = savedMediaUri;
                        final int sizeKb = Math.max(1, Math.round(htmlBytes.length / 1024f));
                        final int nEntries = res.entryCount;
                        final int nScreens = res.attachedScreenCount;
                        runOnUiThread(() -> {
                            if (finalUri != null) {
                                AlertDialog.Builder b = new AlertDialog.Builder(MainActivity.this)
                                        .setTitle("✅ Лог + скрины сохранены (.html)")
                                        .setMessage("Один файл (принимается чатом напрямую!):\n" + uniqueName
                                                + "\n\nПапка: Загрузки (Downloads)"
                                                + "\nЗаписей лога: " + nEntries
                                                + " · Скриншотов: " + nScreens
                                                + "\nРазмер: " + sizeKb + " КБ")
                                        .setPositiveButton("✓ ОК", null)
                                        .setNeutralButton("📂 В другую папку...", (d, w) ->
                                                launchCreateDocumentPicker(uniqueName, "text/html", htmlBytes))
                                        .setNegativeButton("📤 Отправить файл", (d, w) ->
                                                shareBinaryUri(uniqueName, finalUri, "text/html"));
                                b.show();
                            } else {
                                launchCreateDocumentPicker(uniqueName, "text/html", htmlBytes);
                            }
                        });
                    } catch (Exception ex) {
                        ex.printStackTrace();
                        runOnUiThread(() ->
                                Toast.makeText(MainActivity.this, "Ошибка сохранения лога: " + ex.getMessage(), Toast.LENGTH_LONG).show());
                    }
                }).start();
                return true;
            } catch (Exception e) {
                e.printStackTrace();
                return false;
            }
        }

        @JavascriptInterface
        public boolean exportLogTxtArchiveBundle(String rawBaseName, String payloadJsonStr) {
            return exportLogZipBundle(rawBaseName, payloadJsonStr);
        }

        @JavascriptInterface
        public boolean exportLogZipBundle(String rawBaseName, String payloadJsonStr) {
            try {
                final String safeJson = payloadJsonStr != null ? payloadJsonStr : "{}";
                final String safeBase = (rawBaseName != null && !rawBaseName.isEmpty())
                        ? rawBaseName : "Лог_Скрины_Двойная_радость";
                new Thread(() -> {
                    try {
                        LogScreenStore.ZipBundleResult res = LogScreenStore.buildZipBundle(getApplicationContext(), safeJson);
                        final byte[] zipBytes = res.zipBytes;
                        String ts = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
                        String cleanBase = safeBase.replaceAll("\\.(html|zip|log|txt)$", "");
                        // Saved directly with .txt extension so chat upload accepts it immediately without manual renaming!
                        final String uniqueName = cleanBase + "_" + ts + ".txt";

                        Uri savedMediaUri = null;
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                            String[] tryMimes = new String[]{"text/plain", "application/octet-stream"};
                            for (String tryMime : tryMimes) {
                                try {
                                    ContentValues cv = new ContentValues();
                                    cv.put(MediaStore.Downloads.DISPLAY_NAME, uniqueName);
                                    cv.put(MediaStore.Downloads.MIME_TYPE, tryMime);
                                    cv.put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS);
                                    cv.put(MediaStore.Downloads.IS_PENDING, 1);
                                    Uri uri = getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv);
                                    if (uri != null) {
                                        try (OutputStream os = getContentResolver().openOutputStream(uri)) {
                                            if (os == null) throw new java.io.IOException("OutputStream is null");
                                            os.write(zipBytes);
                                            os.flush();
                                        }
                                        ContentValues doneCv = new ContentValues();
                                        doneCv.put(MediaStore.Downloads.IS_PENDING, 0);
                                        getContentResolver().update(uri, doneCv, null, null);
                                        savedMediaUri = uri;
                                        break;
                                    }
                                } catch (Exception ignored) {}
                            }
                        }

                        final Uri finalUri = savedMediaUri;
                        final int sizeKb = Math.max(1, Math.round(zipBytes.length / 1024f));
                        final int nEntries = res.entryCount;
                        final int nScreens = res.attachedScreenCount;
                        runOnUiThread(() -> {
                            if (finalUri != null) {
                                AlertDialog.Builder b = new AlertDialog.Builder(MainActivity.this)
                                        .setTitle("✅ Компактный пакет лога + скринов (.txt)")
                                        .setMessage("Принимается чатом напрямую без переименования!\n" + uniqueName
                                                + "\n\nПапка: Загрузки (Downloads)"
                                                + "\nЗаписей лога: " + nEntries
                                                + " · Скриншотов: " + nScreens
                                                + "\nРазмер: " + sizeKb + " КБ (сжатый пакет)")
                                        .setPositiveButton("✓ ОК", null)
                                        .setNeutralButton("📂 В другую папку...", (d, w) ->
                                                launchCreateDocumentPicker(uniqueName, "text/plain", zipBytes))
                                        .setNegativeButton("📤 Отправить файл", (d, w) ->
                                                shareBinaryUri(uniqueName, finalUri, "text/plain"));
                                b.show();
                            } else {
                                launchCreateDocumentPicker(uniqueName, "text/plain", zipBytes);
                            }
                        });
                    } catch (Exception ex) {
                        ex.printStackTrace();
                        runOnUiThread(() ->
                                Toast.makeText(MainActivity.this, "Ошибка упаковки архива: " + ex.getMessage(), Toast.LENGTH_LONG).show());
                    }
                }).start();
                return true;
            } catch (Exception e) {
                e.printStackTrace();
                return false;
            }
        }

        @JavascriptInterface
        public boolean copyToClipboard(String text) {
            try {
                final String safeText = text != null ? text : "";
                runOnUiThread(() -> {
                    ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                    if (cm != null) {
                        cm.setPrimaryClip(ClipData.newPlainText("Двойная радость — Лог", safeText));
                        Toast.makeText(MainActivity.this, "✓ Скопировано в буфер обмена", Toast.LENGTH_SHORT).show();
                    }
                });
                return true;
            } catch (Exception e) {
                return false;
            }
        }

        @JavascriptInterface
        public boolean saveExportFile(String rawFileName, String content, String mimeType) {
            try {
                final String safeContent = content != null ? content : "";
                final byte[] utf8Bytes = safeContent.getBytes(StandardCharsets.UTF_8);
                final String baseMime = (mimeType != null && !mimeType.isEmpty()) ? mimeType : "text/plain";

                // Add HH-mm-ss timestamp so MediaStore never fails on duplicate filenames in Downloads
                String ts = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
                String fName = (rawFileName != null && !rawFileName.isEmpty()) ? rawFileName : ("export_" + ts + ".log");
                int dot = fName.lastIndexOf('.');
                final String uniqueName = (dot > 0)
                        ? (fName.substring(0, dot) + "_" + ts + fName.substring(dot))
                        : (fName + "_" + ts);

                boolean savedToDownloads = false;
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    String[] tryMimes = uniqueName.endsWith(".log")
                            ? new String[]{"application/octet-stream", "text/plain"}
                            : new String[]{baseMime, "application/octet-stream"};
                    for (String tryMime : tryMimes) {
                        try {
                            ContentValues cv = new ContentValues();
                            cv.put(MediaStore.Downloads.DISPLAY_NAME, uniqueName);
                            cv.put(MediaStore.Downloads.MIME_TYPE, tryMime);
                            cv.put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS);
                            cv.put(MediaStore.Downloads.IS_PENDING, 1);
                            Uri uri = getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv);
                            if (uri != null) {
                                try (OutputStream os = getContentResolver().openOutputStream(uri)) {
                                    if (os == null) {
                                        throw new java.io.IOException("OutputStream is null");
                                    }
                                    os.write(utf8Bytes);
                                    os.flush();
                                }
                                ContentValues doneCv = new ContentValues();
                                doneCv.put(MediaStore.Downloads.IS_PENDING, 0);
                                getContentResolver().update(uri, doneCv, null, null);
                                savedToDownloads = true;
                                break;
                            }
                        } catch (Exception ignored) {}
                    }
                }

                final boolean okDownloads = savedToDownloads;
                final int lineCount = safeContent.isEmpty() ? 0 : safeContent.split("\n").length;
                runOnUiThread(() -> {
                    if (okDownloads) {
                        new AlertDialog.Builder(MainActivity.this)
                                .setTitle("✅ Файл сохранён в «Загрузки»")
                                .setMessage("Имя файла:\n" + uniqueName + "\n\nПапка: Загрузки (Downloads)\nСтрок: " + lineCount)
                                .setPositiveButton("✓ ОК", null)
                                .setNeutralButton("📂 В другую папку...", (d, w) ->
                                        launchCreateDocumentPicker(uniqueName, baseMime, utf8Bytes))
                                .setNegativeButton("📤 Поделиться", (d, w) ->
                                        shareTextContent(uniqueName, safeContent, "text/plain"))
                                .show();
                    } else {
                        launchCreateDocumentPicker(uniqueName, baseMime, utf8Bytes);
                    }
                });
                return true;
            } catch (Exception e) {
                e.printStackTrace();
                return false;
            }
        }

        @JavascriptInterface
        public void setUseHintsInCalc(boolean value) {
            try {
                AppState.get().setUseHintsInCalc(value);
            } catch (Exception ignored) {}
        }

        @JavascriptInterface
        public void setSaveUiScreenshots(boolean value) {
            try {
                AppState.get().setSaveUiScreenshots(value);
            } catch (Exception ignored) {}
        }
    }
}
