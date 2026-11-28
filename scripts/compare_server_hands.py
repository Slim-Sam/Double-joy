#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Сопоставление рук с игрового сервера с большой таблицей переходов.

Источник рук: JSON, полученный с сервера игры (см. Алгоритмы_Двойная_радость.md §5.1/§5.2 —
поля `waits` из CardMasterStartRespS2C/UpdateBattle или очередь из CardMasterViewRespS2C).

Формат входа (JSON):
  {"stage": 12, "hands": [ <рука>, <рука>, ... ]}
  <рука> = список из 3 слотов; слот = один из:
    - строка блоков вида "5_3|1_2|" (id цвета × число, порядок как на сервере)
    - список пар [[5,3],[1,2]]
    - объект {"5":3,"1":2}
  (id цвета: 1 Ягоды … 8 Индейка — те же, что в HandTransitionsData)

Может быть несколько сессий: {"sessions":[{"stage":12,"hands":[...]}, ...]}.

Выход:
  - сводка: сколько переходов уже в таблице (stage/global) и точность предсказания;
  - НОВЫЕ переходы (факт не совпал с таблицей или таблицы нет) — строки вида
    "stage;handKey;nextHandKey", готовые для scripts/make_hand_transitions.py.

Запуск: python3 scripts/compare_server_hands.py server_hands.json
"""
import json, re, sys, os

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
JAVA = os.path.join(ROOT, "double_joy_android_apk/src/ru/doublejoy/solver/HandTransitionsData.java")


def load_tables():
    src = open(JAVA, encoding="utf-8").read()
    stage_rows, global_rows = {}, {}
    m = re.search(r'STAGE_TABLE =\s*"([^"]*)"', src, re.S)
    if m:
        for line in m.group(1).split("\\n"):
            if not line.strip():
                continue
            pr = line.split(";", 2)
            if len(pr) == 3:
                stage_rows[(int(pr[0]), pr[1])] = pr[2]
    m = re.search(r'GLOBAL_TABLE =\s*"([^"]*)"', src, re.S)
    if m:
        for line in m.group(1).split("\\n"):
            if not line.strip():
                continue
            pr = line.split(";", 1)
            if len(pr) == 2:
                global_rows[pr[0]] = pr[1]
    return stage_rows, global_rows


def norm_slot(slot):
    """Слот -> "id_count|id_count|" (блоки в порядке сервера, как в логах)."""
    if isinstance(slot, str):
        s = slot.strip()
        if not s.endswith("|"):
            s += "|"
        return s
    if isinstance(slot, dict):
        return "".join(f"{k}_{v}|" for k, v in slot.items())
    if isinstance(slot, (list, tuple)):
        # [[id,count],...] или [(id,count),...] или [[id,count,....],...] — пара на элемент
        out = []
        for el in slot:
            if isinstance(el, (list, tuple)) and len(el) >= 2:
                out.append(f"{int(el[0])}_{int(el[1])}|")
            else:
                raise ValueError(f"не удалось разобрать блок слота: {el!r}")
        return "".join(out)
    raise ValueError(f"неизвестный формат слота: {slot!r}")


def hand_key(hand):
    if not isinstance(hand, (list, tuple)) or len(hand) != 3:
        return None
    try:
        sigs = [norm_slot(s) for s in hand]
    except ValueError:
        return None
    return ",".join(sorted(sigs))


def sessions_from(data):
    if isinstance(data, dict) and "sessions" in data:
        return data["sessions"]
    if isinstance(data, dict) and "hands" in data:
        return [data]
    if isinstance(data, list):
        return data
    raise ValueError("ожидался {stage,hands} / {sessions:[...]}")


def main():
    if len(sys.argv) < 2:
        print(__doc__)
        sys.exit(2)
    stage_t, global_t = load_tables()
    data = json.load(open(sys.argv[1], encoding="utf-8"))
    pairs = 0            # всего переходов (hand_i -> hand_i+1 в рамках этапа)
    hit_stage = 0        # переход найден в stage-таблице
    hit_global = 0       # не в stage, но найден в global
    correct = 0          # из найденных: факт совпал с таблицей
    new_rows = []        # кандидаты в таблицу
    new_seen = set()
    bad_hands = 0
    for sess in sessions_from(data):
        st = int(sess.get("stage", 0))
        keys = []
        for h in sess.get("hands", []):
            k = hand_key(h)
            if k is None:
                bad_hands += 1
            keys.append(k)
        for i in range(len(keys) - 1):
            a, b = keys[i], keys[i + 1]
            if a is None or b is None:
                continue
            if sess.get("stage") and i + 1 < len(keys):
                pass
            pairs += 1
            pred = stage_t.get((st, a))
            where = "stage"
            if pred is None:
                pred = global_t.get(a)
                where = "global"
            if pred is None:
                where = "нет"
            else:
                if where == "stage":
                    hit_stage += 1
                else:
                    hit_global += 1
                if pred == b:
                    correct += 1
            if where != "нет" and pred != b or where == "нет":
                row = (f"{st};{a};{b}" if st else f"{a};{b}")
                if row not in new_seen:
                    new_seen.add(row)
                    new_rows.append((where, row))
    total_known = hit_stage + hit_global
    print(f"Переходов всего: {pairs}  (плохих рук: {bad_hands})")
    print(f"  в STAGE-таблице: {hit_stage}  ({100*hit_stage//max(pairs,1)}%)")
    print(f"  в GLOBAL-таблице: {hit_global}  ({100*hit_global//max(pairs,1)}%)")
    print(f"  не найдено:       {pairs - total_known}  ({100*(pairs-total_known)//max(pairs,1)}%)")
    print(f"Точность предсказания (из найденных): {correct}/{total_known}"
          + (f" = {100*correct//total_known}%" if total_known else ""))
    print(f"Новых/расходящихся переходов: {len(new_rows)}")
    for w, r in new_rows[:20]:
        print(f"  [{w}] {r}")
    if len(new_rows) > 20:
        print(f"  ... и ещё {len(new_rows)-20}")
    if new_rows and "--out" in sys.argv:
        out = sys.argv[sys.argv.index("--out") + 1]
        with open(out, "w", encoding="utf-8") as f:
            for _, r in new_rows:
                f.write(r + "\n")
        print(f"Записано в {out} (строки можно слить: python3 scripts/make_hand_transitions.py <каталог>)")


if __name__ == "__main__":
    main()
