#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
Эмуляция автотапа «Двойная радость» — зеркало state machine из
CaptureOverlayService (autopilotTick / runApPreCheck / runApPostCheck) + тайминги.
Цель: ПЕРЕД сборкой найти ошибки логики: порядок событий, паузы, стопы,
правило «Только после всех3 стопок → Шаг+Рука», перекрытия панелью.

Запуск: python3 autopilot_emulation.py   (коды выхода:0 = все сценарии ОК)
"""
import sys

# ---- тайминги (зеркало констант Java) ----
AP_HOLD_MS = 250
AP_MOVE_MS = 550
AP_STEP_PAUSE_MS = 2000      # пауза2 с после отпускания стопки
AP_AFTER_STEPHAND_MS = 2000  # пауза2 с после события «Шаг+Рука»
AP_RETRY_MS = 600
CAPTURE_MS = 400             # условное время скрин+CV (оценка)
DRAG_MS = AP_HOLD_MS + AP_MOVE_MS


class AP:
    """Детерминированная копия состояния автопилота."""

    def __init__(self, env):
        self.env = env                      # сценарий: выдаёт результаты проверок
        self.t = 0.0
        self.log = []                       # (time, event)
        # поля как в Java:
        self.autopilotOn = False
        self.apInFlight = False
        self.apTickScheduled = False
        self.apRoundArmed = False
        self.apStepIdx = 0
        self.apIdleRetries = 0
        self.apLastRoundSig = ""
        self.apHandCount = 0
        self.isBusy = False
        self.serviceEnabled = True
        self.label = "▶ Старт автопилот"
        self.manualButtonsEnabled = True    # ✋/⏩/? включены когда автопилот выкл
        self.rounds = 0                     # сколько раз эмитирован Шаг+Рука
        self.stopped_with = None
        self.clicks = []                    # события performClick
        self.drag_fail_pending = False
        self.collapse_tried = False

    # --- журнал ---
    def ev(self, name, **kw):
        self.log.append((round(self.t, 3), name, kw))

    def advance(self, ms):
        self.t += ms / 1000.0

    # --- вспомогательные (зеркало refreshOverlayUi) ---
    def refresh(self):
        self.label = "⏹ Стоп автопилот" if self.autopilotOn else "▶ Старт автопилот"
        self.manualButtonsEnabled = not self.autopilotOn
        if self.autopilotOn:
            self.schedule_tick(0)

    def schedule_tick(self, delay_ms):
        if self.apTickScheduled:
            return
        self.apTickScheduled = True
        # немедленный планировщик: считаем диспетчеризацию мгновенной, время — в tick
        self.pending_tick_at = self.t + delay_ms / 1000.0

    def stop(self, reason):
        was = self.autopilotOn
        self.autopilotOn = False
        self.apInFlight = False
        self.apRoundArmed = False
        self.apStepIdx = 0
        self.stopped_with = reason
        self.ev("STOP", reason=reason, was_on=was)
        self.refresh()

    # =================== ТИК (autopilotTick) ===================
    def tick(self):
        self.apTickScheduled = False
        if not self.autopilotOn:
            return
        if self.apInFlight:
            return
        if self.isBusy:
            self.ev("retry_busy")
            self.schedule_tick(AP_RETRY_MS)
            return
        if not self.serviceEnabled:
            self.stop("⏹ Служба автотапа выключена")
            return

        plan = self.env.plan()
        if plan and plan.get("win"):
            self.stop("🏁 Этап пройден")
            return
        if plan is None:
            # свежий план: событие кнопки «✋ Рука» (не физический тап)
            if not self.env.overlay_ready:
                self.stop("⚠ Оверлей не готов")
                return
            self.click("btnScanHandOnly")
            self.schedule_tick(AP_AFTER_STEPHAND_MS)
            return
        if plan.get("deadlock"):
            self.stop("🛑 Тупик — 🔄/🔨 вручную")
            return
        steps = plan.get("steps", [])
        if not steps:
            self.apIdleRetries += 1
            if self.apIdleRetries > 15:
                self.stop("⚠ Нет ходов (15 попыток)")
                return
            self.schedule_tick(AP_RETRY_MS * 3)
            return

        if not self.apRoundArmed:
            sig = f'{plan["stage"]}:{plan["score"]}:{len(steps)}:{plan["final"]}:{steps[0][0]}>{steps[0][1]}'
            if sig == self.apLastRoundSig:
                self.stop("⏹ Ход не применяется — ? Сверить")
                return
            self.apLastRoundSig = sig
            self.apRoundArmed = True
            self.apStepIdx = 0
            self.apIdleRetries = 0
            self.apHandCount = self.env.hand_count()   # стопок в руке (1..3)
            self.ev("ARM", hand=self.apHandCount, sig=sig)

        if self.apStepIdx >= len(steps):
            # ===== КОНЕЦ РАУНДА =====
            self.apRoundArmed = False
            if plan.get("partial"):
                self.stop(f"⛔ Префикс неполный ({self.apStepIdx}/{self.apHandCount}) — Шаг+Рука НЕ вызываю")
                return
            if self.apHandCount != 3 or self.apStepIdx != 3:
                self.stop(f"⏹ Перенесено {self.apStepIdx} из {self.apHandCount} — «Шаг+Рука» только после всех3")
                return
            self.click("btnStepAndHand")             # событие кнопки
            self.ev("ALL3_MOVED")
            self.schedule_tick(AP_AFTER_STEPHAND_MS)  # пауза2 с
            return

        # ===== ШАГ: скрин → жест → пауза2с → скрин-проверка =====
        self.apInFlight = True
        idx = self.apStepIdx
        slot, cell = steps[idx]
        self.ev("PRE_CAPTURE_START", step=idx + 1, slot=slot, cell=cell)
        self.advance(CAPTURE_MS)

        # --- pre-check (runApPreCheck.onFrames) ---
        if not self.env.pre_slot_present(slot):
            self.apInFlight = False
            self.stop(f"⏹ Стопка слота #{slot} не найдена на скрине")
            return
        frm = self.env.slot_center(slot)
        to = self.env.cell_center(cell)
        if frm is None or to is None:
            self.apInFlight = False
            self.stop("⏹ Нет координат — ✋ Рука")
            return
        cov = self.env.covers(frm) or self.env.covers(to)
        if cov:
            if "панель" in cov and not self.collapse_tried and not self.env.collapsed:
                self.collapse_tried = True
                self.env.collapsed = True
                self.ev("AUTO_COLLAPSE")
                self.advance(350)
                # рекурсивный повтор pre-check (один раз)
                if not self.env.pre_slot_present(slot):
                    self.apInFlight = False
                    self.stop("⏹ Стопка не найдена (повтор)")
                    return
                cov = self.env.covers(frm) or self.env.covers(to)
                if cov:
                    self.apInFlight = False
                    self.stop(f"📛 Окно закрывает ({cov}) — сдвинь окно")
                    return
            else:
                self.apInFlight = False
                self.stop(f"📛 Плавающее окно закрывает ({cov}) — сдвинь окно")
                return

        # --- жест ---
        self.ev("DRAG_START", frm=frm, to=to)
        self.advance(DRAG_MS)
        if self.env.drag_fails():
            self.apInFlight = False
            self.stop("⏹ Жест не выполнен")
            return
        self.ev("DRAG_RELEASED")
        self.advance(AP_STEP_PAUSE_MS)   # пауза2 с после отпускания

        # --- post-check (runApPostCheck) ---
        self.ev("POST_CAPTURE_START", cell=cell)
        self.advance(CAPTURE_MS)
        if not self.env.verify_placed(cell):
            self.apInFlight = False
            self.stop(f"⏹ Установка не подтвердилась (кл. #{cell}) — ? Сверить")
            return
        self.ev("STEP_OK", step=idx + 1)
        self.apStepIdx = idx + 1
        self.apInFlight = False
        self.schedule_tick(0)

    def click(self, btn):
        self.clicks.append((round(self.t, 3), btn))
        self.ev("CLICK_EVENT", btn=btn)
        if btn == "btnStepAndHand" and hasattr(self.env, "round"):
            self.env.round += 1  # после события — новый скан → новый план

    # --- драйвер: гоняем тики, пока есть запланированные или включён ---
    def run(self, max_ticks=400):
        for _ in range(max_ticks):
            if not self.autopilotOn and not self.apTickScheduled:
                break
            if self.apTickScheduled and self.t >= getattr(self, "pending_tick_at", 0):
                self.tick()
            elif not self.apTickScheduled:
                break
            else:
                # время до ближайшего тика
                self.t = max(self.t, self.pending_tick_at)
                self.tick()
        return self


# ================= СЦЕНАРИИ =================
class Env:
    """Игровое окружение со скриптованными результатами."""

    def __init__(self, steps_per_round, hand=3, verify_ok=True, drag_ok=True,
                 slot_ok=True, coverage=None, deadlock=False, partial=False,
                 win_after_rounds=99, overlay_ready=True, plan_none=False):
        self.round = 0
        self.steps_per_round = list(steps_per_round)  # [[(slot,cell),...], ...]
        self.hand = hand
        self.verify_ok = verify_ok
        self.drag_ok = drag_ok
        self.slot_ok = slot_ok
        self.coverage = coverage          # None | "панель" | "нижняя полоса"
        self.deadlock = deadlock
        self.partial = partial
        self.win_after_rounds = win_after_rounds
        self.overlay_ready = overlay_ready
        self.plan_none = plan_none
        self.collapsed = False
        self._drag_calls = 0

    def plan(self):
        if self.plan_none:
            return None
        if self.round >= self.win_after_rounds:
            return {"win": True}
        if self.deadlock:
            return {"deadlock": True, "steps": [(1, 8)]}
        if self.round >= len(self.steps_per_round):
            return {"win": True}  # сценарий исчерпан → считаем победой
        steps = self.steps_per_round[self.round]
        return {"win": False, "deadlock": False,
                "partial": self.partial and self.round == len(self.steps_per_round) - 1,
                "steps": steps, "stage": 11, "score": self.round * 100,
                "final": self.round * 100 + 30}

    def hand_count(self):
        return self.hand

    def pre_slot_present(self, slot):
        return self.slot_ok

    def slot_center(self, slot):
        return (300.0, 1700.0)

    def cell_center(self, cell):
        return (540.0, 900.0)

    def covers(self, pt):
        if self.coverage and not self.collapsed:
            return self.coverage
        if self.coverage == "нижняя полоса":
            return self.coverage  # полоса не сворачивается панелью
        return None

    def drag_fails(self):
        self._drag_calls += 1
        return not self.drag_ok

    def verify_placed(self, cell):
        return self.verify_ok


def new_ap(env):
    ap = AP(env)
    return ap


def start(ap):
    ap.autopilotOn = True
    ap.collapse_tried = False
    ap.ev("START")
    ap.refresh()
    ap.schedule_tick(0)


def run_scenario(name, env, expect_stop_contains=None, expect_rounds=None,
                 expect_no_click_after_stop=True, min_gaps=None):
    ap = new_ap(env)
    start(ap)
    ap.run()
    ok = True
    msgs = []
    if expect_stop_contains is not None:
        if ap.stopped_with is None:
            ok = False; msgs.append("ожидался стоп, но автопилот не остановился")
        elif expect_stop_contains not in ap.stopped_with:
            ok = False; msgs.append(f'стоп «{ap.stopped_with}» не содержит «{expect_stop_contains}»')
    if expect_rounds is not None:
        got = len([c for c in ap.clicks if c[1] == "btnStepAndHand"])
        if got != expect_rounds:
            ok = False; msgs.append(f"Шаг+Рука кликнуто {got}, ожидалось {expect_rounds}")
    # инварианты таймингов между DRAG_RELEASED → POST_CAPTURE_START ≥2с
    if min_gaps:
        prev = None
        for t, ev, kw in ap.log:
            if ev == "DRAG_RELEASED":
                prev = t
            if ev == "POST_CAPTURE_START" and prev is not None:
                if t - prev < 2.0 - 1e-6:
                    ok = False; msgs.append(f"пауза после отпускания {t - prev:.2f}с <2с")
                prev = None
        # пауза после клика Шаг+Рука до следующего события ≥2с
        for i, (t, ev, kw) in enumerate(ap.log):
            if ev == "CLICK_EVENT" and kw.get("btn") == "btnStepAndHand":
                nxt = next((lt for lt, _, _ in ap.log[i + 1:] if lt > t), None)
                if nxt is not None and nxt - t < 2.0 - 1e-6:
                    ok = False; msgs.append(f"пауза после Шаг+Рука {nxt - t:.2f}с <2с")
    if ap.autopilotOn:
        ok = False; msgs.append("автопилот остался включён после прогона")
    if ap.manualButtonsEnabled is False:
        ok = False; msgs.append("ручные кнопки остались заблокированными")
    print(f'  [{"OK " if ok else "FAIL"}] {name}' + ('' if ok else ' — ' + '; '.join(msgs)))
    return ok, ap


def main():
    results = []

    # S1: счастливый путь —2 раунда по3 стопки, всё подтверждено
    env = Env([[ (1,8),(2,15),(3,12) ], [ (2,8),(1,15),(3,18) ]], hand=3)
    ok, ap = run_scenario("S1 happy:3+3 стопки →2×(Шаг+Рука+пауза2с) → победа",
                          env, expect_stop_contains="🏁", expect_rounds=2, min_gaps=True)
    print(f'      timeline: ' + ' | '.join(f'{t:.1f}s {e}' for t, e, _ in ap.log if e in
          ("START", "ARM", "DRAG_START", "DRAG_RELEASED", "POST_CAPTURE_START", "STEP_OK", "CLICK_EVENT", "STOP")))
    results.append(ok)

    # S2: частичный план (2 из3) → стоп, БЕЗ клика
    env = Env([[ (1,8),(2,15) ]], hand=3, partial=True)
    ok, _ = run_scenario("S2 partial-план → стоп ⛔, Шаг+Рука НЕ эмитится",
                         env, expect_stop_contains="Префикс неполный", expect_rounds=0)
    results.append(ok)

    # S3: в руке2 стопки (аномалия) → стоп «только после всех3»
    env = Env([[ (1,8),(2,15) ]], hand=2)
    ok, _ = run_scenario("S3 рука из2 стопок → стоп, клика нет",
                         env, expect_stop_contains="только после всех3", expect_rounds=0)
    results.append(ok)

    # S4: deadlock → стоп до шагов
    env = Env([], deadlock=True)
    ok, _ = run_scenario("S4 deadlock → стоп 🛑 до переносов", env, expect_stop_contains="Тупик", expect_rounds=0)
    results.append(ok)

    # S5: стопка не найдена на скрине перед переносом
    env = Env([[ (1,8),(2,15),(3,12) ]], slot_ok=False)
    ok, _ = run_scenario("S5 pre-скрин: стопка не найдена → стоп", env, expect_stop_contains="не найдена", expect_rounds=0)
    results.append(ok)

    # S6: панель закрывает точку → автосворачивание → повтор → ОК
    env = Env([[ (1,8),(2,15),(3,12) ]], coverage="панель")
    ok, ap = run_scenario("S6 перекрытие «панель» → автосворачивание → повтор → ОК",
                          env, expect_stop_contains="🏁", expect_rounds=1)
    if not any(e == "AUTO_COLLAPSE" for _, e, _ in ap.log):
        ok = False; print("  [FAIL] S6: не было автосворачивания")
    results.append(ok)

    # S7: нижняя полоса закрывает цель → стоп (без авто-фикса)
    env = Env([[ (1,8),(2,15),(3,12) ]], coverage="нижняя полоса")
    ok, _ = run_scenario("S7 перекрытие «нижняя полоса» → стоп 📛", env, expect_stop_contains="закрывает", expect_rounds=0)
    results.append(ok)

    # S8: жест не прошёл (служба выключилась)
    env = Env([[ (1,8),(2,15),(3,12) ]], drag_ok=False)
    ok, _ = run_scenario("S8 drag-fail → стоп", env, expect_stop_contains="Жест не выполнен", expect_rounds=0)
    results.append(ok)

    # S9: post-скрин не подтверждает установку
    env = Env([[ (1,8),(2,15),(3,12) ]], verify_ok=False)
    ok, _ = run_scenario("S9 post-скрин: установка не подтверждена → стоп", env,
                         expect_stop_contains="не подтвердилась", expect_rounds=0)
    results.append(ok)

    # S10: служба выключена до старта
    env = Env([[ (1,8),(2,15),(3,12) ]])
    ap = new_ap(env); ap.serviceEnabled = False; start(ap); ap.run()
    ok = ap.stopped_with is not None and "Служба" in ap.stopped_with and not ap.clicks
    print(f'  [{"OK " if ok else "FAIL"}] S10 служба выкл → стоп до всего')
    results.append(ok)

    # S11: окно не готово → стоп, кликов нет
    env = Env([], overlay_ready=False, plan_none=True)
    ap = new_ap(env); ap.autopilotOn = True; ap.refresh(); ap.schedule_tick(0); ap.run()
    ok = ap.stopped_with is not None and "не готов" in ap.stopped_with and not ap.clicks
    print(f'  [{"OK " if ok else "FAIL"}] S11 оверлей не готов → стоп,0 кликов')
    results.append(ok)

    # S12: правило «нельзя физически нажимать» — все переходы только CLICK_EVENT
    env = Env([[ (1,8),(2,15),(3,12) ]])
    ok, ap = run_scenario("S12 «Шаг+Рука» только как событие кнопки", env,
                          expect_stop_contains="🏁", expect_rounds=1)
    physical = [e for _, e, _ in ap.log if e == "PHYSICAL_TAP"]
    if physical:
        ok = False; print("  [FAIL] S12: обнаружен физический тап")
    results.append(ok)

    # S13: занятость сканом → retry, не потеря шага
    env = Env([[ (1,8),(2,15),(3,12) ]])
    ap = new_ap(env); start(ap)
    ap.isBusy = True
    ap.run(max_ticks=5)
    busy_seen = any(e == "retry_busy" for _, e, _ in ap.log)
    ap.isBusy = False
    ap.run()
    ok = busy_seen and ap.stopped_with is not None and "🏁" in ap.stopped_with
    print(f'  [{"OK " if ok else "FAIL"}] S13 busy-scan → retry600мс и продолжение')
    results.append(ok)

    # S14: нет прогресса (одинаковый sig дважды) — смоделировано через повтор плана
    #      (окружение отдаёт один и тот же план после попытки; в Java это ловится по apLastRoundSig)
    class StuckEnv(Env):
        def __init__(self):
            super().__init__([[ (1,8),(2,15),(3,12) ]], hand=3)
            self.calls = 0
        def plan(self):
            self.calls += 1
            if self.calls <= 1:
                return super().plan()
            # рука/поле якобы не изменились — тот же sig
            return {"win": False, "deadlock": False, "partial": False,
                    "steps": [(1,8),(2,15),(3,12)], "stage": 11, "score": 0, "final": 30}
        def hand_count(self):
            return 3
    env = StuckEnv()
    ap = new_ap(env); start(ap); ap.run()
    ok = ap.stopped_with is not None and "не применяется" in ap.stopped_with
    print(f'  [{"OK " if ok else "FAIL"}] S14 ход не применяется → стоп (нет бесконечного цикла)')
    results.append(ok)

    # S15: свёртка панели не помогла (покрытие «панель» после collapse у «полосы» не бывает) —
    #      проверка: повторный pre после автосворачивания действительно выполняется
    env = Env([[ (1,8),(2,15),(3,12) ]], coverage="панель")
    ap = new_ap(env); start(ap); ap.run()
    seq = [e for _, e, _ in ap.log]
    ok = seq.count("DRAG_START") == 3 and seq.count("AUTO_COLLAPSE") == 1
    print(f'  [{"OK " if ok else "FAIL"}] S15 автосворачивание →1 повтор pre → жесты идут дальше (3/3)')
    results.append(ok)

    # ---- статическая геометрия (по формулам кода, экран1080×2400, density2.75) ----
    print("\n  Статическая геометрия оверлея (1080×2400, dp=2.75px, оценка):")
    W, H, d = 1080, 2400, 2.75
    board_top = int(0.26 * H)          # по кадру этапа11 (~26% высоты)
    board_bot = int(0.65 * H)
    hand_top = int(0.68 * H)
    panel_w = min(int(0.80 * W) - int(8 * d), int(432 * d))   # paneW-8dp vs dp(432)
    panel_y = board_top - int(82 * d)                          # boardTop - sdp(82)
    panel_h_est = int(230 * d)                                 # WRAP_CONTENT оценка
    strip_h = int(72 * d)
    strip_bottom = hand_top                                   # якорь «выше руки»
    strip_top = strip_bottom - strip_h
    print(f"    доска: y {board_top}..{board_bot}; рука: y {hand_top}..{int(0.76*H)}")
    print(f"    верхняя панель: x≈{(W-panel_w)//2}..{(W+panel_w)//2}, y {panel_y}..{panel_y+panel_h_est}"
          f" → {'⚠ ПЕРЕКРЫВАЕТ верх доски' if panel_y + panel_h_est > board_top else 'ок'}")
    print(f"    нижняя полоса: y {strip_top}..{strip_bottom}"
          f" → {'⚠ может задеть нижний ряд доски' if strip_top < board_bot else 'ок'} (зазор {board_bot and hand_top-board_bot}px < полосы {strip_h}px)"
          if hand_top - board_bot < strip_h else f"    нижняя полоса: y {strip_top}..{strip_bottom} — зазор достаточен")

    print(f"\n  ИТОГ: {sum(results)}/{len(results)} сценариев OK")
    return 0 if all(results) else 1


if __name__ == "__main__":
    sys.exit(main())
