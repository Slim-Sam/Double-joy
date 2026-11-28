#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Обучение: генерация HandTransitionsData.java из логов игроков.

Источник: uploads/Лог_*.txt и uploads/прочитано/Лог_*.txt (все логи).
Результат: double_joy_android_apk/src/ru/doublejoy/solver/HandTransitionsData.java
Эмпирика: (этап, рука) -> следующая рука детерминирована в ~98% случаев
(H(next|current)=0.03 бит по 7 сессиям / 534 рукам). Запуск: python3 scripts/make_hand_transitions.py
"""
import zipfile, os, re, glob, collections, sys

RU2ID = {"Ягоды":1,"Клён":2,"Колос":3,"Орёл":4,"Хлеб":5,"Яблоко":6,"Жёлудь":7,"Индейка":8}
hand_re = re.compile(r'\[РУКА\] ✋ Скан руки \(Этап (\d+), Счёт \d+/\d+, экран: \d+, режим: \w+\): (.+)$')

def slot_to_sig(slot_text):
    out = []
    for p in [x.strip() for x in slot_text.split("→") if x.strip()]:
        m = re.search(r'([А-Яа-яЁё]+)×(\d+)', p)
        if not m: return None
        cid = RU2ID.get(m.group(1))
        if cid is None: return None
        out.append(f"{cid}_{int(m.group(2))}")
    return "|".join(out) + "|"

def hand_key(slots_text):
    sigs = []
    for s in slots_text.split("·"):
        sig = slot_to_sig(s.strip())
        if sig is None: return None
        sigs.append(sig)
    return ",".join(sorted(sigs)) if len(sigs) == 3 else None

def main():
    root = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
    # Способ БЕЗ выкачки логов в git: положи свежие логи-зипы в отдельный каталог
    # (например /tmp/newlogs, скачать можно через GitHub API) и укажи его:
    #   python3 scripts/make_hand_transitions.py /tmp/newlogs
    # Таблица ПРИСОЕДИНЯЕТСЯ к уже накопленной (существующие переходы сохраняются).
    extra_dirs = [a for a in sys.argv[1:]]
    files = []
    for d in extra_dirs:
        files += sorted(glob.glob(os.path.join(d, "*.txt")))
    if not files:
        files = sorted(glob.glob(f"{root}/uploads/прочитано/Лог_*.txt")) + sorted(glob.glob(f"{root}/uploads/Лог_*.txt"))
    seen = set(); uniq = []
    for f in files:
        b = os.path.basename(f)
        if b in seen: continue
        seen.add(b); uniq.append(f)
    if not uniq:
        print("логи не найдены", file=sys.stderr); sys.exit(1)
    by_stage = collections.defaultdict(lambda: collections.defaultdict(collections.Counter))
    global_t = collections.defaultdict(collections.Counter)
    sessions = 0; hands_total = 0
    for f in uniq:
        name = os.path.basename(f).replace("Лог_Скрины_Двойная_радость_", "").replace(".txt", "")
        d = "/tmp/alllogs/" + name
        if not os.path.isdir(d):
            try:
                with zipfile.ZipFile(f) as z: z.extractall(d)
            except Exception: continue
        p = os.path.join(d, "session.log")
        if not os.path.exists(p): continue
        sessions += 1
        hands = []
        for line in open(p, encoding="utf-8", errors="replace"):
            if "[РУКА]" in line:
                m = hand_re.search(line.rstrip())
                if m:
                    k = hand_key(m.group(2))
                    if k: hands.append((int(m.group(1)), k))
        hands_total += len(hands)
        for i in range(len(hands)-1):
            st, k = hands[i]
            if hands[i+1][0] != st: continue
            by_stage[st][k][hands[i+1][1]] += 1
            global_t[k][hands[i+1][1]] += 1
    # --- слияние с уже накопленной таблицей (не теряем историю) ---
    out_path = os.path.join(root, "double_joy_android_apk/src/ru/doublejoy/solver/HandTransitionsData.java")
    if os.path.exists(out_path):
        import re as _re
        cur = open(out_path, encoding="utf-8").read()
        m_st = _re.search(r'STAGE_TABLE =\s*"([^"]*)"', cur, _re.S)
        if m_st:
            for line in m_st.group(1).split("\\n"):
                if not line.strip(): continue
                pr = line.split(";", 2)
                if len(pr) == 3 and pr[1] not in by_stage[int(pr[0])]:
                    by_stage[int(pr[0])][pr[1]][pr[2]] += 1000  # вес истории
        m_gl = _re.search(r'GLOBAL_TABLE =\s*"([^"]*)"', cur, _re.S)
        if m_gl:
            for line in m_gl.group(1).split("\\n"):
                if not line.strip(): continue
                pr = line.split(";", 2) if line.count(";") == 2 else line.split(";", 1)
                if len(pr) == 2 and pr[0] not in global_t:
                    global_t[pr[0]][pr[1]] += 1000
    stage_rows = []
    for st in sorted(by_stage):
        for k in sorted(by_stage[st]):
            ctr = by_stage[st][k]
            if len(ctr) == 1: stage_rows.append((st, k, next(iter(ctr))))
    global_rows = []
    for k in sorted(global_t):
        ctr = global_t[k]
        if len(ctr) != 1: continue
        nk = next(iter(ctr))
        if not any(k in m and (len(m[k]) != 1 or next(iter(m[k])) != nk) for m in by_stage.values()):
            global_rows.append((k, nk))
    def java_str(rows, stage_mode):
        parts = [f"{st};{k};{nk}" for st,k,nk in rows] if stage_mode else [f"{k};{nk}" for k,nk in rows]
        return "\\n".join(parts)
    src = f"""package ru.doublejoy.solver;

/**
 * Эмпирическая таблица переходов рук, обучена на реальных логах игроков
 * (uploads/прочитано/Лог_*.txt). Строки: "stage;handKey;nextHandKey"
 * и глобально "handKey;nextHandKey".
 * handKey = 3 слота через ",", каждый слот "id_count|id_count|" (слоты отсортированы).
 * По логам: (этап, рука) → следующая рука детерминирована в ~98% случаев
 * (энтропия H(next|current)=0.03 бит). Обучено на {sessions} сессиях, {hands_total} рук.
 * Генератор: scripts/make_hand_transitions.py (перегенерировать после НОВЫХ логов).
 */
public final class HandTransitionsData {{
    private HandTransitionsData() {{}}
    public static final String STAGE_TABLE =
        "{java_str(stage_rows, True)}";
    public static final String GLOBAL_TABLE =
        "{java_str(global_rows, False)}";
}}
"""
    out = os.path.join(root, "double_joy_android_apk/src/ru/doublejoy/solver/HandTransitionsData.java")
    open(out, "w", encoding="utf-8").write(src)
    print(f"OK: {sessions} сессий, {hands_total} рук -> {len(stage_rows)} stage / {len(global_rows)} global переходов; {os.path.getsize(out)} байт")

if __name__ == "__main__":
    main()
