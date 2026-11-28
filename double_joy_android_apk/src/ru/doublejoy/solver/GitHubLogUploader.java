package ru.doublejoy.solver;

import android.content.Context;
import android.util.Base64;
import android.util.Log;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Sends the packed session-log zip (LogScreenStore.buildZipBundle: session.log + all action
 * screenshots + manifest + HTML viewer) to a GitHub repository via the REST Contents API
 * using a user-supplied Personal Access Token (PAT).
 *
 * Security: the PAT is passed per-call from WebView localStorage, used ONLY in the
 * Authorization header of the upload request, and is never written to log files, status
 * JSON or the archive itself. Filenames follow the existing repo/uploads convention:
 *   Лог_Скрины_Двойная_радость_ЭтапN_yyyyMMdd_HHmmss.txt  (a ZIP container with .txt name).
 */
public final class GitHubLogUploader {
    private static final String TAG = "GitHubLogUploader";
    private static final ExecutorService EXEC = Executors.newSingleThreadExecutor();
    private static final AtomicBoolean RUNNING = new AtomicBoolean(false);

    private static volatile String state = "idle"; // idle | running | ok | error
    private static volatile String message = "";
    private static volatile String fileUrl = "";
    private static volatile String fileName = "";

    private GitHubLogUploader() {}

    /**
     * Starts an async upload. Returns false if an upload is already in progress
     * (the UI then just keeps polling the status).
     */
    public static boolean start(final Context ctx, final String rawBaseName, final String payloadJson,
                                final String rawRepo, final String rawBranch,
                                final String rawDir, final String rawPat) {
        if (!RUNNING.compareAndSet(false, true)) return false;
        state = "running";
        message = "Проверка настроек…";
        fileUrl = "";
        fileName = "";

        final String pat = rawPat != null ? rawPat.trim() : "";
        final String repo = rawRepo != null ? rawRepo.trim() : "";
        if (pat.isEmpty()) {
            state = "error";
            message = "Не задан GitHub PAT — настройки → «Отправка логов на GitHub»";
            RUNNING.set(false);
            return true;
        }
        if (!repo.contains("/") || repo.startsWith("/") || repo.endsWith("/")) {
            state = "error";
            message = "Репозиторий должен быть в формате owner/name (например Slim-Sam/Double-joy)";
            RUNNING.set(false);
            return true;
        }

        final String baseName = (rawBaseName != null && !rawBaseName.isEmpty())
                ? rawBaseName : "Лог_Скрины_Двойная_радость";
        final String branch = (rawBranch == null || rawBranch.trim().isEmpty())
                ? "master" : rawBranch.trim();
        String dir = (rawDir == null) ? "" : rawDir.trim().replaceAll("^/+|/+$", "");
        final String dirFinal = dir;

        EXEC.execute(new Runnable() {
            @Override public void run() {
                try {
                    doUpload(ctx, baseName, payloadJson, repo, branch, dirFinal, pat);
                } catch (Exception e) {
                    Log.w(TAG, "upload failed", e);
                    state = "error";
                    message = "Ошибка: " + (e.getMessage() != null ? e.getMessage()
                            : e.getClass().getSimpleName());
                } finally {
                    RUNNING.set(false);
                }
            }
        });
        return true;
    }

    /** Small JSON status for polling from the WebView (never contains the PAT). */
    public static String statusJson() {
        JSONObject o = new JSONObject();
        try {
            o.put("state", state);
            o.put("message", message);
            o.put("url", fileUrl);
            o.put("file", fileName);
        } catch (Exception ignored) {}
        return o.toString();
    }

    private static void doUpload(Context ctx, String baseName, String payloadJson,
                                 String repo, String branch, String dir, String pat) throws Exception {
        message = "Сборка архива (лог + скрины)…";
        LogScreenStore.ZipBundleResult zip = LogScreenStore.buildZipBundle(ctx, payloadJson);
        if (zip == null || zip.zipBytes == null || zip.zipBytes.length == 0) {
            throw new Exception("Пустой архив лога");
        }

        // Same naming convention as the local export and the existing files in uploads/.
        String ts = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
        String cleanBase = baseName.replaceAll("\\.(html|zip|log|txt)$", "")
                .replaceAll("[\\\\/:*?\"<>|]", "_");
        String name = cleanBase + "_" + ts + ".txt";
        fileName = name;

        String pathPart = dir.isEmpty() ? "" : dir + "/";
        String encName = URLEncoder.encode(name, "UTF-8").replace("+", "%20");
        String apiUrl = "https://api.github.com/repos/" + repo + "/contents/" + pathPart + encName;

        int sizeKb = Math.max(1, zip.zipBytes.length / 1024);
        message = "Кодирование " + sizeKb + " КБ…";
        String contentB64 = Base64.encodeToString(zip.zipBytes, Base64.NO_WRAP);

        JSONObject body = new JSONObject();
        body.put("message", "Лог " + name + " — автоотправка из приложения (скринов: "
                + zip.attachedScreenCount + ")");
        body.put("content", contentB64);
        body.put("branch", branch);

        message = "Отправка в " + repo + " (" + sizeKb + " КБ)…";
        HttpURLConnection conn = (HttpURLConnection) new URL(apiUrl).openConnection();
        try {
            conn.setConnectTimeout(30000);
            conn.setReadTimeout(240000);
            conn.setRequestMethod("PUT");
            conn.setDoOutput(true);
            conn.setRequestProperty("Authorization", "Bearer " + pat);
            conn.setRequestProperty("Accept", "application/vnd.github+json");
            conn.setRequestProperty("X-GitHub-Api-Version", "2022-11-28");
            conn.setRequestProperty("User-Agent", "double-joy-solver");
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            byte[] bodyBytes = body.toString().getBytes(StandardCharsets.UTF_8);
            conn.setFixedLengthStreamingMode(bodyBytes.length);
            OutputStream os = conn.getOutputStream();
            try {
                os.write(bodyBytes);
                os.flush();
            } finally {
                os.close();
            }

            int code = conn.getResponseCode();
            String resp = readLimited(code >= 400 ? conn.getErrorStream() : conn.getInputStream());
            if (code >= 200 && code < 300) {
                String htmlUrl = "";
                try {
                    JSONObject r = new JSONObject(resp);
                    JSONObject c = r.optJSONObject("content");
                    if (c != null) htmlUrl = c.optString("html_url", "");
                } catch (Exception ignored) {}
                state = "ok";
                fileUrl = htmlUrl;
                message = "✅ " + name + " · " + sizeKb + " КБ · скринов: " + zip.attachedScreenCount;
            } else {
                String errMsg;
                try {
                    JSONObject r = new JSONObject(resp);
                    errMsg = r.optString("message", resp);
                } catch (Exception e) {
                    errMsg = resp;
                }
                if (errMsg == null || errMsg.isEmpty()) errMsg = "нет тела ответа";
                if (errMsg.length() > 240) errMsg = errMsg.substring(0, 240);
                if (code == 401) errMsg += " (проверь PAT)";
                else if (code == 403) errMsg += " (нет прав Contents: Read and write или rate limit)";
                else if (code == 404) errMsg += " (репозиторий/ветка не найдены или PAT без доступа)";
                state = "error";
                fileUrl = "";
                message = "HTTP " + code + ": " + errMsg;
            }
        } finally {
            conn.disconnect();
        }
    }

    private static String readLimited(InputStream in) {
        if (in == null) return "";
        try {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int total = 0;
            int n;
            while ((n = in.read(buf)) > 0 && total < 65536) {
                int w = Math.min(n, 65536 - total);
                bos.write(buf, 0, w);
                total += w;
            }
            in.close();
            return bos.toString("UTF-8");
        } catch (Exception e) {
            return "";
        }
    }
}
