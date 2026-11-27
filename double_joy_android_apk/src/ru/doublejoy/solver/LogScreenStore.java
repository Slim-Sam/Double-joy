package ru.doublejoy.solver;

import android.content.Context;
import android.graphics.Bitmap;
import android.util.Base64;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Stores compressed per-action screenshots on internal app storage and packs the complete
 * log + action-linked screenshots + structured JSON + HTML viewer into ONE single .zip file.
 */
public final class LogScreenStore {

    private static final String DIR_NAME = "log_screens";
    private static final int MAX_STORED_SCREENS = 480;
    private static final int TARGET_MAX_WIDTH = 640;
    private static final int JPEG_QUALITY = 78;
    private static final AtomicInteger COUNTER = new AtomicInteger(1);

    private LogScreenStore() {}

    private static File getScreensDir(Context ctx) {
        File dir = new File(ctx.getFilesDir(), DIR_NAME);
        if (!dir.exists()) {
            //noinspection ResultOfMethodCallIgnored
            dir.mkdirs();
        }
        return dir;
    }

    public static synchronized String saveScreenBitmap(Context ctx, Bitmap src, String tagHint) {
        if (ctx == null || src == null || src.isRecycled() || src.getWidth() <= 0 || src.getHeight() <= 0) {
            return null;
        }
        try {
            File dir = getScreensDir(ctx);
            int origW = src.getWidth();
            int origH = src.getHeight();
            Bitmap scaled = src;
            if (origW > TARGET_MAX_WIDTH) {
                int newW = TARGET_MAX_WIDTH;
                int newH = Math.max(120, Math.round((float) origH * newW / origW));
                scaled = Bitmap.createScaledBitmap(src, newW, newH, true);
            }

            String ts = new SimpleDateFormat("HHmmss", Locale.US).format(new Date());
            String safeTag = sanitizeTag(tagHint);
            int seq = COUNTER.getAndIncrement();
            String fileName = "scr_" + System.currentTimeMillis() + "_" + seq + "_" + ts + "_" + safeTag + ".jpg";
            File outFile = new File(dir, fileName);

            try (FileOutputStream fos = new FileOutputStream(outFile)) {
                scaled.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, fos);
                fos.flush();
            }
            if (scaled != src) {
                scaled.recycle();
            }
            pruneOldFilesIfNeeded(dir);
            return fileName;
        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
    }

    private static String sanitizeTag(String raw) {
        if (raw == null || raw.isEmpty()) return "ACT";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < raw.length() && sb.length() < 16; i++) {
            char ch = raw.charAt(i);
            if (Character.isLetterOrDigit(ch) || ch == '_' || ch == '-') {
                sb.append(ch);
            }
        }
        return sb.length() > 0 ? sb.toString() : "ACT";
    }

    private static void pruneOldFilesIfNeeded(File dir) {
        try {
            File[] files = dir.listFiles((d, name) -> name != null && name.endsWith(".jpg"));
            if (files == null || files.length <= MAX_STORED_SCREENS) return;
            Arrays.sort(files, Comparator.comparingLong(File::lastModified));
            int toDelete = files.length - MAX_STORED_SCREENS;
            for (int i = 0; i < toDelete; i++) {
                //noinspection ResultOfMethodCallIgnored
                files[i].delete();
            }
        } catch (Exception ignored) {}
    }

    public static synchronized void clearAllScreens(Context ctx) {
        if (ctx == null) return;
        try {
            File dir = getScreensDir(ctx);
            File[] files = dir.listFiles();
            if (files != null) {
                for (File f : files) {
                    //noinspection ResultOfMethodCallIgnored
                    f.delete();
                }
            }
        } catch (Exception ignored) {}
    }

    public static byte[] readScreenBytes(Context ctx, String screenId) {
        if (ctx == null || screenId == null || screenId.isEmpty()) return null;
        if (screenId.contains("/") || screenId.contains("\\") || screenId.contains("..")) return null;
        try {
            File f = new File(getScreensDir(ctx), screenId);
            if (!f.exists() || !f.isFile()) return null;
            byte[] buf = new byte[(int) f.length()];
            try (FileInputStream fis = new FileInputStream(f)) {
                int off = 0;
                while (off < buf.length) {
                    int r = fis.read(buf, off, buf.length - off);
                    if (r <= 0) break;
                    off += r;
                }
            }
            return buf;
        } catch (Exception e) {
            return null;
        }
    }

    public static String readScreenDataUri(Context ctx, String screenId) {
        byte[] bytes = readScreenBytes(ctx, screenId);
        if (bytes == null || bytes.length == 0) return "";
        return "data:image/jpeg;base64," + Base64.encodeToString(bytes, Base64.NO_WRAP);
    }

    public static final class ZipBundleResult {
        public byte[] zipBytes;
        public int entryCount;
        public int attachedScreenCount;
    }

    public static final class HtmlBundleResult {
        public byte[] htmlBytes;
        public int entryCount;
        public int attachedScreenCount;
    }

    /**
     * Builds ONE single self-contained .html file (MIME text/html — directly accepted by Arena.ai and browsers!)
     * containing:
     *  - Full visual side-by-side timeline of every #action and its screenshot
     *  - Embedded <script id="dj-session-log" type="text/plain"> (full session.log text)
     *  - Embedded <script id="dj-manifest-json" type="application/json"> (structured checkpoints & metadata)
     *  - Embedded <script id="dj-screens-json" type="application/json"> (deduplicated Base64 JPEGs keyed by screenFile)
     */
    public static HtmlBundleResult buildHtmlBundle(Context ctx, String payloadJsonStr) throws Exception {
        JSONObject root = new JSONObject(payloadJsonStr != null ? payloadJsonStr : "{}");
        int stageNo = root.optInt("stageNo", 1);
        int curScore = root.optInt("curScore", 0);
        String solveMode = root.optString("solveMode", "balanced");
        int rotationDeg = root.optInt("rotationDeg", 0);
        JSONArray entries = root.optJSONArray("entries");
        if (entries == null) entries = new JSONArray();

        Map<String, String> screenIdToVirtualName = new HashMap<>();
        Map<String, StringBuilder> screenIdSeqRange = new HashMap<>();
        Map<String, String> screenIdTime = new HashMap<>();
        Map<String, String> screenIdTag = new HashMap<>();
        Map<String, Boolean> screenIdIsUi = new HashMap<>();

        for (int i = 0; i < entries.length(); i++) {
            JSONObject e = entries.optJSONObject(i);
            if (e == null) continue;
            int seq = e.optInt("seq", i + 1);
            String seqPad = String.format(Locale.US, "%03d", seq);
            String timeRaw = e.optString("time", "000000").replaceAll("[^0-9]", "");
            if (timeRaw.isEmpty()) timeRaw = "000000";
            String tag = sanitizeTag(e.optString("tag", "ACT"));

            String[] pairIds = {e.optString("uiScreenId", ""), e.optString("screenId", "")};
            for (int k = 0; k < 2; k++) {
                String sid = pairIds[k];
                if (sid.isEmpty()) continue;
                byte[] check = readScreenBytes(ctx, sid);
                if (check == null || check.length == 0) continue;
                if (!screenIdSeqRange.containsKey(sid)) {
                    screenIdSeqRange.put(sid, new StringBuilder(seqPad));
                    screenIdTime.put(sid, timeRaw);
                    screenIdTag.put(sid, tag);
                    screenIdIsUi.put(sid, k == 0);
                } else {
                    StringBuilder sb = screenIdSeqRange.get(sid);
                    if (sb != null && sb.indexOf(seqPad) < 0) {
                        sb.append('-').append(seqPad);
                    }
                    String prevTag = screenIdTag.get(sid);
                    if (prevTag != null && !prevTag.contains(tag)) {
                        screenIdTag.put(sid, prevTag + "_" + tag);
                    }
                }
            }
        }

        for (Map.Entry<String, StringBuilder> me : screenIdSeqRange.entrySet()) {
            String sid = me.getKey();
            String uiSuffix = Boolean.TRUE.equals(screenIdIsUi.get(sid)) ? "_ВИД" : "";
            String virtName = "screens/" + me.getValue().toString() + "_" + screenIdTime.get(sid) + "_" + screenIdTag.get(sid) + uiSuffix + ".jpg";
            screenIdToVirtualName.put(sid, virtName);
        }

        JSONObject screensJson = new JSONObject();
        String[] sids = screenIdToVirtualName.keySet().toArray(new String[0]);
        Arrays.sort(sids, Comparator.comparing(screenIdToVirtualName::get));
        int writtenScreens = 0;
        for (String sid : sids) {
            byte[] imgBytes = readScreenBytes(ctx, sid);
            if (imgBytes != null && imgBytes.length > 0) {
                String dataUri = "data:image/jpeg;base64," + Base64.encodeToString(imgBytes, Base64.NO_WRAP);
                screensJson.put(screenIdToVirtualName.get(sid), dataUri);
                writtenScreens++;
            }
        }

        StringBuilder plainLog = new StringBuilder(16384);
        plainLog.append("=== ЛОГ РЕШАЛКИ «ДВОЙНАЯ РАДОСТЬ» С ПРИКРЕПЛЁННЫМИ СКРИНАМИ ===\n");
        plainLog.append("Этап: ").append(stageNo)
                .append(" | Текущий счёт: ").append(curScore)
                .append(" | Режим расчёта: ").append(solveMode)
                .append(" | Ручной ракурс: ").append(rotationDeg).append("°")
                .append(" | Записей: ").append(entries.length())
                .append(" | Скриншотов: ").append(writtenScreens).append("\n");
        plainLog.append("================================================================\n");

        StringBuilder cardsHtml = new StringBuilder(32768);
        JSONArray manifestEntries = new JSONArray();

        for (int i = 0; i < entries.length(); i++) {
            JSONObject e = entries.optJSONObject(i);
            if (e == null) continue;
            int seq = e.optInt("seq", i + 1);
            String fullTs = e.optString("fullTs", e.optString("time", ""));
            String tag = e.optString("tag", "ИНФО");
            String plain = e.optString("plain", "");
            String uiSid = e.optString("uiScreenId", "");
            String sid = e.optString("screenId", "");
            String uiVirtPath = uiSid.isEmpty() ? null : screenIdToVirtualName.get(uiSid);
            String virtPath = sid.isEmpty() ? null : screenIdToVirtualName.get(sid);

            plainLog.append('[').append(fullTs).append("] [#").append(seq).append("] [").append(tag).append("] ").append(plain).append('\n');
            if (uiVirtPath != null) {
                plainLog.append("    -> 📸 СКРИН С ОКОШКОМ #").append(seq).append(" [").append(tag).append("]: ").append(uiVirtPath).append('\n');
            }
            if (virtPath != null) {
                plainLog.append("    -> 📸 СКРИН ДЕЙСТВИЯ #").append(seq).append(" [").append(tag).append("]: ").append(virtPath).append('\n');
            }

            JSONArray subs = e.optJSONArray("subLinesPlain");
            StringBuilder subTextHtml = new StringBuilder();
            if (subs != null) {
                for (int j = 0; j < subs.length(); j++) {
                    String sl = subs.optString(j, "");
                    plainLog.append("    -> ").append(sl).append('\n');
                    if (j > 0) subTextHtml.append('\n');
                    subTextHtml.append("→ ").append(escapeHtml(sl));
                }
            }

            JSONObject me = new JSONObject();
            me.put("seq", seq);
            me.put("fullTs", fullTs);
            me.put("tag", tag);
            me.put("plain", plain);
            if (uiVirtPath != null) me.put("uiScreenFile", uiVirtPath);
            if (virtPath != null) me.put("screenFile", virtPath);
            if (subs != null) me.put("subLinesPlain", subs);
            if (e.has("checkpoint")) me.put("checkpoint", e.opt("checkpoint"));
            manifestEntries.put(me);

            cardsHtml.append("<div class=\"card\" id=\"act-").append(seq).append("\"><div class=\"txt\">")
                    .append("<div class=\"hdr\"><b>#").append(seq).append("</b> · ").append(escapeHtml(fullTs))
                    .append(" · <span class=\"tag\">").append(escapeHtml(tag)).append("</span></div>")
                    .append("<div class=\"main\">").append(escapeHtml(plain)).append("</div>");
            if (subTextHtml.length() > 0) {
                cardsHtml.append("<div class=\"sub\">").append(subTextHtml).append("</div>");
            }
            cardsHtml.append("</div>");
            if (uiVirtPath != null) {
                cardsHtml.append("<div class=\"scr\"><img data-scr=\"").append(escapeHtml(uiVirtPath))
                        .append("\" alt=\"#").append(seq).append(" UI\"/><div class=\"cap\">🪟 С окошком: ")
                        .append(escapeHtml(uiVirtPath)).append("</div></div>");
            }
            if (virtPath != null) {
                cardsHtml.append("<div class=\"scr\"><img data-scr=\"").append(escapeHtml(virtPath))
                        .append("\" alt=\"#").append(seq).append("\"/><div class=\"cap\">📸 Чистый: ")
                        .append(escapeHtml(virtPath)).append("</div></div>");
            }
            cardsHtml.append("</div>\n");
        }

        JSONObject manifestRoot = new JSONObject();
        manifestRoot.put("exportedAt", new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(new Date()));
        manifestRoot.put("stageNo", stageNo);
        manifestRoot.put("curScore", curScore);
        manifestRoot.put("solveMode", solveMode);
        manifestRoot.put("rotationDeg", rotationDeg);
        if (root.has("currentSnapshot")) {
            manifestRoot.put("currentSnapshot", root.opt("currentSnapshot"));
        }
        manifestRoot.put("entries", manifestEntries);

        String safePlainLog = plainLog.toString().replace("</script", "<\\/script").replace("</SCRIPT", "<\\/SCRIPT");
        String safeManifestJson = manifestRoot.toString().replace("</", "<\\/");
        String safeScreensJson = screensJson.toString().replace("</", "<\\/");

        StringBuilder outHtml = new StringBuilder(safePlainLog.length() + safeManifestJson.length() + safeScreensJson.length() + cardsHtml.length() + 4096);
        outHtml.append("<!DOCTYPE html>\n<html lang=\"ru\"><head><meta charset=\"utf-8\"/>")
                .append("<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\"/>")
                .append("<title>Лог + Скрины — Этап ").append(stageNo).append(" (Счёт ").append(curScore).append(")</title>")
                .append("<style>")
                .append("body{font-family:system-ui,-apple-system,sans-serif;background:#0b1120;color:#e2e8f0;margin:0;padding:12px;}")
                .append(".top{display:flex;flex-wrap:wrap;gap:10px;align-items:center;justify-content:space-between;margin-bottom:12px;background:#111827;border:1px solid #334155;padding:10px 12px;border-radius:10px;}")
                .append("h1{font-size:16px;margin:0;color:#fde68a;}")
                .append(".meta{font-size:12px;color:#94a3b8;margin-top:3px;}")
                .append(".card{background:#151f32;border:1px solid #334155;border-radius:10px;padding:10px 12px;margin-bottom:10px;display:flex;gap:12px;flex-wrap:wrap;align-items:flex-start;}")
                .append(".txt{flex:1 1 300px;min-width:240px;}")
                .append(".hdr{font-size:12px;color:#94a3b8;margin-bottom:4px;}")
                .append(".tag{background:#1e293b;color:#38bdf8;padding:1px 6px;border-radius:4px;font-weight:700;}")
                .append(".main{font-size:13.5px;font-weight:700;margin-bottom:5px;}")
                .append(".sub{font-size:12px;color:#cbd5e1;line-height:1.45;white-space:pre-wrap;}")
                .append(".scr{flex:0 0 auto;text-align:center;}")
                .append(".scr img{max-width:220px;border-radius:8px;border:1px solid #475569;display:block;cursor:pointer;transition:max-width .15s;}")
                .append(".scr img.big{max-width:min(92vw,520px);}")
                .append(".cap{font-size:10.5px;color:#34d399;margin-top:3px;}")
                .append("</style></head><body>\n")
                .append("<div class=\"top\"><div><h1>📜 Лог со скринами — Этап ").append(stageNo)
                .append(" · Счёт ").append(curScore).append("</h1>")
                .append("<div class=\"meta\">Режим: ").append(escapeHtml(solveMode))
                .append(" · Ракурс: ").append(rotationDeg).append("° · Записей: ").append(entries.length())
                .append(" · Скриншотов: ").append(writtenScreens)
                .append(" (нажмите на скрин для увеличения)</div></div></div>\n")
                .append(cardsHtml)
                .append("<script id=\"dj-session-log\" type=\"text/plain\">\n").append(safePlainLog).append("</script>\n")
                .append("<script id=\"dj-manifest-json\" type=\"application/json\">").append(safeManifestJson).append("</script>\n")
                .append("<script id=\"dj-screens-json\" type=\"application/json\">").append(safeScreensJson).append("</script>\n")
                .append("<script>(function(){try{var m=JSON.parse(document.getElementById('dj-screens-json').textContent||'{}');")
                .append("var imgs=document.querySelectorAll('img[data-scr]');for(var i=0;i<imgs.length;i++){var im=imgs[i];var k=im.getAttribute('data-scr');if(m[k])im.src=m[k];")
                .append("im.onclick=function(){this.classList.toggle('big');};}}catch(e){}})();</script>\n")
                .append("</body></html>\n");

        HtmlBundleResult out = new HtmlBundleResult();
        out.htmlBytes = outHtml.toString().getBytes(StandardCharsets.UTF_8);
        out.entryCount = entries.length();
        out.attachedScreenCount = writtenScreens;
        return out;
    }

    /**
     * Builds ONE single .zip archive containing:
     *  - session.log (plain text log with exact -> 📸 СКРИН ДЕЙСТВИЯ #N: screens/... links)
     *  - screens/NNN_HHMMSS_TAG.jpg (every captured screen linked by action #seq!)
     *  - manifest.json (structured machine-readable log + checkpoints + screen paths)
     *  - viewer.html (offline visual report showing each action and its screenshot side by side)
     */
    public static ZipBundleResult buildZipBundle(Context ctx, String payloadJsonStr) throws Exception {
        JSONObject root = new JSONObject(payloadJsonStr != null ? payloadJsonStr : "{}");
        int stageNo = root.optInt("stageNo", 1);
        int curScore = root.optInt("curScore", 0);
        String solveMode = root.optString("solveMode", "balanced");
        int rotationDeg = root.optInt("rotationDeg", 0);
        JSONArray entries = root.optJSONArray("entries");
        if (entries == null) entries = new JSONArray();

        // First pass: group entries that share the exact same physical capture (e.g. ⏩ Шаг+Рука creates #N [ХОД] + #N+1 [РУКА])
        Map<String, String> screenIdToZipPath = new HashMap<>();
        Map<String, StringBuilder> screenIdSeqRange = new HashMap<>();
        Map<String, String> screenIdTime = new HashMap<>();
        Map<String, String> screenIdTag = new HashMap<>();
        Map<String, Boolean> screenIdIsUi = new HashMap<>();

        for (int i = 0; i < entries.length(); i++) {
            JSONObject e = entries.optJSONObject(i);
            if (e == null) continue;
            int seq = e.optInt("seq", i + 1);
            String seqPad = String.format(Locale.US, "%03d", seq);
            String timeRaw = e.optString("time", "000000").replaceAll("[^0-9]", "");
            if (timeRaw.isEmpty()) timeRaw = "000000";
            String tag = sanitizeTag(e.optString("tag", "ACT"));

            String[] pairIds = {e.optString("uiScreenId", ""), e.optString("screenId", "")};
            for (int k = 0; k < 2; k++) {
                String sid = pairIds[k];
                if (sid.isEmpty()) continue;
                byte[] check = readScreenBytes(ctx, sid);
                if (check == null || check.length == 0) continue;
                if (!screenIdSeqRange.containsKey(sid)) {
                    screenIdSeqRange.put(sid, new StringBuilder(seqPad));
                    screenIdTime.put(sid, timeRaw);
                    screenIdTag.put(sid, tag);
                    screenIdIsUi.put(sid, k == 0);
                } else {
                    StringBuilder sb = screenIdSeqRange.get(sid);
                    if (sb != null && sb.indexOf(seqPad) < 0) {
                        sb.append('-').append(seqPad);
                    }
                    String prevTag = screenIdTag.get(sid);
                    if (prevTag != null && !prevTag.contains(tag)) {
                        screenIdTag.put(sid, prevTag + "_" + tag);
                    }
                }
            }
        }

        for (Map.Entry<String, StringBuilder> me : screenIdSeqRange.entrySet()) {
            String sid = me.getKey();
            String seqLabel = me.getValue().toString();
            String t = screenIdTime.get(sid);
            String tg = screenIdTag.get(sid);
            String uiSuffix = Boolean.TRUE.equals(screenIdIsUi.get(sid)) ? "_ВИД" : "";
            String zipPath = "screens/" + seqLabel + "_" + t + "_" + tg + uiSuffix + ".jpg";
            screenIdToZipPath.put(sid, zipPath);
        }

        StringBuilder plainLog = new StringBuilder(16384);
        plainLog.append("=== ЛОГ РЕШАЛКИ «ДВОЙНАЯ РАДОСТЬ» С ПРИКРЕПЛЁННЫМИ СКРИНАМИ ===\n");
        plainLog.append("Этап: ").append(stageNo)
                .append(" | Текущий счёт: ").append(curScore)
                .append(" | Режим расчёта: ").append(solveMode)
                .append(" | Ручной ракурс: ").append(rotationDeg).append("°")
                .append(" | Записей: ").append(entries.length())
                .append(" | Скриншотов: ").append(screenIdToZipPath.size()).append("\n");
        plainLog.append("================================================================\n");

        StringBuilder htmlViewer = new StringBuilder(24576);
        htmlViewer.append("<!DOCTYPE html><html lang=\"ru\"><head><meta charset=\"utf-8\"/>")
                .append("<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\"/>")
                .append("<title>Лог + Скрины — Этап ").append(stageNo).append("</title>")
                .append("<style>")
                .append("body{font-family:system-ui,-apple-system,sans-serif;background:#0b1120;color:#e2e8f0;margin:0;padding:14px;}")
                .append("h1{font-size:18px;margin:0 0 12px;color:#fde68a;}")
                .append(".card{background:#151f32;border:1px solid #334155;border-radius:10px;padding:10px 12px;margin-bottom:10px;display:flex;gap:12px;flex-wrap:wrap;align-items:flex-start;}")
                .append(".txt{flex:1 1 320px;min-width:250px;}")
                .append(".hdr{font-size:12px;color:#94a3b8;margin-bottom:4px;}")
                .append(".tag{background:#1e293b;color:#38bdf8;padding:1px 6px;border-radius:4px;font-weight:700;}")
                .append(".main{font-size:13.5px;font-weight:700;margin-bottom:5px;}")
                .append(".sub{font-size:12px;color:#cbd5e1;line-height:1.45;white-space:pre-wrap;}")
                .append(".scr{flex:0 0 auto;text-align:center;}")
                .append(".scr img{max-width:230px;border-radius:8px;border:1px solid #475569;display:block;}")
                .append(".scr a{font-size:11px;color:#34d399;text-decoration:none;display:inline-block;margin-top:3px;}")
                .append("</style></head><body>")
                .append("<h1>📜 Лог игры со скринами — Этап ").append(stageNo)
                .append(" · Счёт ").append(curScore).append(" · Режим ").append(escapeHtml(solveMode)).append("</h1>\n");

        JSONArray manifestEntries = new JSONArray();

        for (int i = 0; i < entries.length(); i++) {
            JSONObject e = entries.optJSONObject(i);
            if (e == null) continue;
            int seq = e.optInt("seq", i + 1);
            String fullTs = e.optString("fullTs", e.optString("time", ""));
            String tag = e.optString("tag", "ИНФО");
            String plain = e.optString("plain", "");
            String uiSid = e.optString("uiScreenId", "");
            String sid = e.optString("screenId", "");
            String zipUiPath = uiSid.isEmpty() ? null : screenIdToZipPath.get(uiSid);
            String zipScreenPath = sid.isEmpty() ? null : screenIdToZipPath.get(sid);

            plainLog.append('[').append(fullTs).append("] [#").append(seq).append("] [").append(tag).append("] ").append(plain).append('\n');
            if (zipUiPath != null) {
                plainLog.append("    -> 📸 СКРИН С ОКОШКОМ #").append(seq).append(" [").append(tag).append("]: ").append(zipUiPath).append('\n');
            }
            if (zipScreenPath != null) {
                plainLog.append("    -> 📸 СКРИН ДЕЙСТВИЯ #").append(seq).append(" [").append(tag).append("]: ").append(zipScreenPath).append('\n');
            }

            JSONArray subs = e.optJSONArray("subLinesPlain");
            StringBuilder subTextHtml = new StringBuilder();
            if (subs != null) {
                for (int j = 0; j < subs.length(); j++) {
                    String sl = subs.optString(j, "");
                    plainLog.append("    -> ").append(sl).append('\n');
                    if (j > 0) subTextHtml.append('\n');
                    subTextHtml.append("→ ").append(escapeHtml(sl));
                }
            }

            JSONObject me = new JSONObject();
            me.put("seq", seq);
            me.put("fullTs", fullTs);
            me.put("tag", tag);
            me.put("plain", plain);
            if (zipUiPath != null) {
                me.put("uiScreenFile", zipUiPath);
            }
            if (zipScreenPath != null) {
                me.put("screenFile", zipScreenPath);
            }
            if (subs != null) {
                me.put("subLinesPlain", subs);
            }
            if (e.has("checkpoint")) {
                me.put("checkpoint", e.opt("checkpoint"));
            }
            manifestEntries.put(me);

            htmlViewer.append("<div class=\"card\" id=\"act-").append(seq).append("\"><div class=\"txt\">")
                    .append("<div class=\"hdr\"><b>#").append(seq).append("</b> · ").append(escapeHtml(fullTs))
                    .append(" · <span class=\"tag\">").append(escapeHtml(tag)).append("</span></div>")
                    .append("<div class=\"main\">").append(escapeHtml(plain)).append("</div>");
            if (subTextHtml.length() > 0) {
                htmlViewer.append("<div class=\"sub\">").append(subTextHtml).append("</div>");
            }
            htmlViewer.append("</div>");
            if (zipUiPath != null) {
                htmlViewer.append("<div class=\"scr\"><a href=\"").append(zipUiPath).append("\" target=\"_blank\">")
                        .append("<img src=\"").append(zipUiPath).append("\" loading=\"lazy\" alt=\"#").append(seq).append(" UI\"/>")
                        .append("🪟 С окошком: ").append(zipUiPath).append("</a></div>");
            }
            if (zipScreenPath != null) {
                htmlViewer.append("<div class=\"scr\"><a href=\"").append(zipScreenPath).append("\" target=\"_blank\">")
                        .append("<img src=\"").append(zipScreenPath).append("\" loading=\"lazy\" alt=\"#").append(seq).append("\"/>")
                        .append("📸 Чистый: ").append(zipScreenPath).append("</a></div>");
            }
            htmlViewer.append("</div>\n");
        }
        htmlViewer.append("</body></html>\n");

        JSONObject manifestRoot = new JSONObject();
        manifestRoot.put("exportedAt", new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(new Date()));
        manifestRoot.put("stageNo", stageNo);
        manifestRoot.put("curScore", curScore);
        manifestRoot.put("solveMode", solveMode);
        manifestRoot.put("rotationDeg", rotationDeg);
        if (root.has("currentSnapshot")) {
            manifestRoot.put("currentSnapshot", root.opt("currentSnapshot"));
        }
        manifestRoot.put("entries", manifestEntries);

        ByteArrayOutputStream baos = new ByteArrayOutputStream(262144);
        int writtenScreens = 0;
        try (ZipOutputStream zos = new ZipOutputStream(baos)) {
            writeZipBytes(zos, "session.log", plainLog.toString().getBytes(StandardCharsets.UTF_8));
            writeZipBytes(zos, "manifest.json", manifestRoot.toString(2).getBytes(StandardCharsets.UTF_8));
            writeZipBytes(zos, "viewer.html", htmlViewer.toString().getBytes(StandardCharsets.UTF_8));

            // Write screenshots in sorted order
            String[] sids = screenIdToZipPath.keySet().toArray(new String[0]);
            Arrays.sort(sids, Comparator.comparing(screenIdToZipPath::get));
            for (String sid : sids) {
                byte[] imgBytes = readScreenBytes(ctx, sid);
                if (imgBytes != null && imgBytes.length > 0) {
                    writeZipBytes(zos, screenIdToZipPath.get(sid), imgBytes);
                    writtenScreens++;
                }
            }
            zos.finish();
        }

        ZipBundleResult out = new ZipBundleResult();
        out.zipBytes = baos.toByteArray();
        out.entryCount = entries.length();
        out.attachedScreenCount = writtenScreens;
        return out;
    }

    private static void writeZipBytes(ZipOutputStream zos, String entryName, byte[] data) throws Exception {
        ZipEntry ze = new ZipEntry(entryName);
        zos.putNextEntry(ze);
        zos.write(data);
        zos.closeEntry();
    }

    private static String escapeHtml(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;");
    }
}
