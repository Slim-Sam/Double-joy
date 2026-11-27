#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
Конвейер анализа логов + скриншотов решалки «Двойная радость».

Что делает:
1. Парсит session.log -> структурированные записи (время, тип, счёт, текст).
2. Для каждого скриншота вычисляет измеримый «отпечаток»:
   разрешение, средне-яркость, насыщенность, число уникальных цветов,
   доминирующие цвета (палитра).
3. Сшивает скриншоты с логом по имени файла (скриншот упоминается в лог-записи).
4. Выявляет корреляции и аномалии:
   - действие без привязанного скриншота
   - скриншот без лог-записи
   - повторяющиеся скриншоты (двойники)
   - выбросы яркости/палитры (возможные проблемы с распознаванием)
   - этап/тип действия по ключевым словам.

Используется: PIL, numpy. OCR не обязателен — лог содержит текстовое описание.
"""
import os, re, sys, json, csv, hashlib
from pathlib import Path
from collections import Counter, defaultdict
from PIL import Image
import numpy as np

ROOT = Path(__file__).resolve().parent
SCREENS_DIR = ROOT / "screens"
LOG_PATH = ROOT / "extracted_session.log"

# ---------- 1. Парсинг лога ----------
RE_TIME = re.compile(r"\[(\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2})\] \[#(\d+)\] \[([^\]]+)\] (.*)")
RE_SCREEN = re.compile(r"(screens/[^\s]+\.jpg)")
RE_SCORE = re.compile(r"Счёт:\s*(\d+)\s*/(\d+)")
RE_STAGE = re.compile(r"Этап\s*(\d+)")

def parse_log(path):
    records = []
    lines = path.read_text(encoding="utf-8", errors="replace").splitlines()
    i = 0
    while i < len(lines):
        line = lines[i].strip()
        m = RE_TIME.match(line)
        if not m:
            i += 1
            continue
        ts, num, etype, text = m.groups()
        # Собираем все экраны: из основной строки + из строчек-продолжений
        all_text = text
        j = i + 1
        while j < len(lines):
            nxt = lines[j].strip()
            if RE_TIME.match(nxt) or nxt.startswith("==="):
                break
            all_text += " " + nxt
            j += 1
        rec = {
            "num": int(num), "time": ts, "type": etype, "text": text,
            "screens": RE_SCREEN.findall(all_text),
            "score": None, "stage": None,
        }
        ms = RE_SCORE.search(text)
        if ms:
            rec["score"] = f"{ms.group(1)}/{ms.group(2)}"
        ms2 = RE_STAGE.search(text)
        if ms2:
            rec["stage"] = int(ms2.group(1))
        records.append(rec)
        i = j
    return records

# ---------- 2. Отпечаток скриншота ----------
def fingerprint(path):
    im = Image.open(path).convert("RGB")
    arr = np.asarray(im)
    h, w = arr.shape[:2]
    # яркость (0-255)
    lum = 0.2126*arr[...,0] + 0.7152*arr[...,1] + 0.0722*arr[...,2]
    brightness = float(lum.mean())
    # насыщенность (среднеотклонение по каналам)
    mx = arr.max(axis=2); mn = arr.min(axis=2)
    sat = float((mx-mn).mean()) if mx.size else 0.0
    # уникальные цвета (квантизованные, 4 бита/канал)
    small = arr[::4, ::4].astype(np.int16)
    q = (small // 16).reshape(-1, 3)
    uniq = np.unique(q, axis=0)
    uniq_count = int(len(uniq))
    # доминирующие цвета (топ-5 по частоте квантизированных цветов)
    keys = q[:,0]*64 + q[:,1]*8 + q[:,2]
    cnt = Counter(keys.tolist())
    top = cnt.most_common(5)
    palette = []
    for k, c in top:
        r = (k//64)*16 + 8; g = ((k//8)%8)*16 + 8; b = (k%8)*16 + 8
        palette.append({"rgb": [r,g,b], "frac": round(c/len(keys), 4)})
    # хэш для поиска двойников (не переиспользуем h)
    digest = hashlib.md5(arr.tobytes()).hexdigest()[:10]
    return {
        "width": w, "height": h, "mode": f"{w}x{h}",
        "brightness": round(brightness, 2),
        "saturation": round(sat, 2),
        "unique_colors": uniq_count,
        "palette": palette,
        "md5": digest,
        "size_kb": round(path.stat().st_size/1024, 1),
    }

# ---------- 3. Сшивка ----------
def main():
    log = parse_log(LOG_PATH)
    print(f"[1] Лог: {len(log)} записей")

    screens = {}
    for f in sorted(SCREENS_DIR.iterdir()):
        if f.suffix.lower() not in (".jpg", ".jpeg", ".png"):
            continue
        screens[f.name] = fingerprint(f)
    print(f"[2] Скриншоты: {len(screens)}")

    # Сбор ссылок на скриншоты из лога
    used_screens = set()
    log_with_screens = []
    for r in log:
        for s in r["screens"]:
            bn = os.path.basename(s)
            used_screens.add(bn)
            r["screen_names"] = r.get("screen_names", []) + [bn]
        log_with_screens.append(r)

    orphan_screens = set(screens) - used_screens
    missing_in_dir = used_screens - set(screens)

    # Двойники (по md5)
    md5_map = defaultdict(list)
    for name, fp in screens.items():
        md5_map[fp["md5"]].append(name)
    duplicates = {h: names for h, names in md5_map.items() if len(names) > 1}

    # Сводка
    print("\n[3] Корреляция лог ↔ скриншоты")
    print(f"    Записей в логе:        {len(log)}")
    print(f"    Упоминаний скриншотов: {len(used_screens)}")
    print(f"    Скриншотов на диске:   {len(screens)}")
    print(f"    Двойников (одинаковых): {len(duplicates)} групп")
    if orphan_screens:
        print(f"    ⚠ Скриншоты без лог-записи: {len(orphan_screens)}")
    if missing_in_dir:
        print(f"    ⚠ Упомянуты в логе, но нет на диске: {len(missing_in_dir)}")

    # ---------- 4. Анализ по типам ----------
    print("\n[4] Распределение типов действий")
    type_counter = Counter(r["type"] for r in log)
    for t, c in type_counter.most_common():
        print(f"    {t}: {c}")

    # ---------- 5. Быстрая палитра для групп ----------
    print("\n[5] Палитра/яркость по типам (по первому скриншоту каждой группы)")
    seen_mode = set()
    for r in log:
        for bn in r.get("screen_names", []):
            fp = screens.get(bn)
            if fp and fp["mode"] not in seen_mode:
                seen_mode.add(fp["mode"])
                print(f"    {r['type']:8} -> {fp['mode']:9} яркость={fp['brightness']:6.2f} цветов={fp['unique_colors']}")

    # ---------- 6. Выбросы яркости ----------
    print("\n[6] Выбросы яркости (порог ±2.0σ)")
    br = np.array([fp["brightness"] for fp in screens.values()])
    if len(br) > 2:
        mu, sd = br.mean(), br.std()
        for name, fp in screens.items():
            if abs(fp["brightness"] - mu) > 2.0 * sd:
                print(f"    ⚠ {name}: яркость={fp['brightness']} (среднее {mu:.1f}±{sd:.1f})")

    # ---------- 7. CSV отчёт ----------
    csv_path = ROOT / "report.csv"
    with csv_path.open("w", newline="", encoding="utf-8") as f:
        w = csv.writer(f)
        w.writerow(["screen", "md5", "mode", "brightness", "saturation",
                    "unique_colors", "size_kb", "dup_group",
                    "log_num", "log_type", "log_time", "log_score"])
        # привязка скриншота к лог-записи
        bn_to_rec = {}
        for r in log:
            for bn in r.get("screen_names", []):
                bn_to_rec.setdefault(bn, r)
        for name, fp in screens.items():
            rec = bn_to_rec.get(name)
            dup = ";".join(md5_map[fp["md5"]]) if len(md5_map[fp["md5"]]) > 1 else ""
            w.writerow([name, fp["md5"], fp["mode"], fp["brightness"],
                        fp["saturation"], fp["unique_colors"], fp["size_kb"],
                        dup,
                        rec["num"] if rec else "",
                        rec["type"] if rec else "",
                        rec["time"] if rec else "",
                        rec["score"] if rec else ""])
    print(f"\n[7] Отчёт: {csv_path}")

    # JSON для машинного чтения
    json_path = ROOT / "report.json"
    json_path.write_text(json.dumps({
        "log_records": log,
        "screens": screens,
        "duplicates": duplicates,
        "orphan_screens": sorted(orphan_screens),
        "missing_in_dir": sorted(missing_in_dir),
    }, ensure_ascii=False, indent=2), encoding="utf-8")
    print(f"[8] JSON:   {json_path}")

if __name__ == "__main__":
    main()
