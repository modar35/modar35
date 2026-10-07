#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
Параметрический генератор раздвоенной выхлопной трассы.
Opel Astra H 1.8 (Z18XE / Z18XER), кузов Caravan (универсал), G09.

Трассу модель собирает «как для изготовления»: последовательность ПРЯМЫХ УЧАСТКОВ и
ГИБОВ стандартного ряда (7.5/11.25/15/22.5/30/37.5/45°, R = k·OD, мандрел).
Раскрой = pipe straights + bend schedule, то есть это то, что просит гибщик.

Выход в --out:
  3d/    STL по деталям + сборка + GLB (3D-просмотр)
  dxf/   чертёж: вид сбоку/сверху, размеры, таблица раскроя (R2010, мм, 1:1)
  scad/  OpenSCAD (править текстом -> F6 -> STL; STEP через FreeCAD)
  png/   render_3d_iso.png, drawing_side_top.png, bends.png, clearances.png
  cutlist.csv  bends.csv  bom.csv  spec.json  README.md

Координаты (мм): X=0 на оси передних колёс, +X назад; Y=0 в плоскости симметрии,
+Y влево (для LHD — сторона водителя); Z=0 опорная плоскость, +Z вверх.
"""

from __future__ import annotations

import argparse
import csv
import json
import math
import os
from dataclasses import dataclass, field

import numpy as np

SS_RHO = 7900.0
R_GAS = 287.0
GAMMA = 1.34
PSI = 6894.757
STD_BENDS = [7.5, 11.25, 15.0, 22.5, 30.0, 37.5, 45.0, 60.0, 90.0]


# ======================================================================================
# 1. ИСХОДНЫЕ ДАННЫЕ
# ======================================================================================


@dataclass
class Car:
    """Габариты и компоновочные ориентиры Astra H Caravan (G09), снаряж. ~1360 кг."""

    length: float = 4515.0
    wheelbase: float = 2703.0          # Caravan: база на 89 мм больше хэтчбека (2614)
    width: float = 1753.0
    height: float = 1500.0
    ground_clearance: float = 165.0
    track_front: float = 1488.0
    track_rear: float = 1479.0
    front_overhang: float = 885.0
    floor_rear: float = 520.0          # низ пола кузова за задней осью
    tank_x0: float = 2260.0
    tank_x1: float = 2680.0
    tank_y: float = 330.0
    tank_h: float = 125.0
    tank_bottom: float = 405.0         # НИЗ бака (над трассой)
    axle_x: float = 2703.0
    beam_top: float = 430.0
    beam_x: float = 120.0
    wheelhouse_y: float = 800.0
    brake_line_y: float = 585.0
    brake_x0: float = 2350.0
    brake_x1: float = 3000.0
    hitch_x: float = 3480.0
    diff_x: float = 3630.0
    oem_outlet: tuple = (-150.0, -40.0, 390.0)   # срез стока после ката/2-го зонда — МЕРИТЬ НА МАШИНЕ

    @property
    def rear_overhang(self) -> float:
        return self.length - self.wheelbase - self.front_overhang


@dataclass
class Preset:
    name: str
    main_od: float
    leg_od: float
    tail_od: float
    tip_od: float
    wall: float
    box_l: float
    box_w: float
    box_h: float
    core_id: float
    resonator: bool
    res_d: float
    res_l: float
    note: str


PRESETS = {
    "tour": Preset(
        name="tour", main_od=60.5, leg_od=51.0, tail_od=51.0, tip_od=76.0, wall=1.5,
        box_l=420, box_w=250, box_h=150, core_id=45.0,
        resonator=True, res_d=127.0, res_l=420.0,
        note="Тихий тур для универсала: Ø60.5, резонатор перед Y, две низкие овальные банки "
             "420×250×150, 2×Ø76 из-под бампера без подрезки. Звук: +2…4 дБ(А) к стоку."),
    "sport": Preset(
        name="sport", main_od=63.5, leg_od=57.0, tail_od=51.0, tip_od=89.0, wall=1.5,
        box_l=380, box_w=230, box_h=140, core_id=51.0,
        resonator=False, res_d=127.0, res_l=0.0,
        note="Спорт: Ø63.5, Y 63.5→2×Ø57, банки короче, 2×Ø89 в окнах бампера. +6…10 дБ(А); "
             "на атмосферном 1.8 прирост ≈0, риск гула 1800-2800 об/мин выше."),
    "stockish": Preset(
        name="stockish", main_od=57.0, leg_od=51.0, tail_od=45.0, tip_od=63.5, wall=1.2,
        box_l=450, box_w=250, box_h=150, core_id=42.0,
        resonator=True, res_d=114.0, res_l=450.0,
        note="Раздвоенная, но по шуму/потоку близко к стоку: для споров про техосмотр и для "
             "машин с ГБО. +0…2 дБ(А)."),
}

ENGINE = {
    "Z18XE": dict(kw=92.0, hp=125, rpm_max=6300, torque=170.0, torque_rpm=3800,
                  disp_l=1.8, bsfc=330.0, years="2004-2007"),
    "Z18XER": dict(kw=104.0, hp=140, rpm_max=6400, torque=175.0, torque_rpm=3800,
                   disp_l=1.8, bsfc=325.0, years="2004-2015, twin-VVT"),
    "Z18XER_85": dict(kw=85.0, hp=115, rpm_max=6000, torque=170.0, torque_rpm=3800,
                      disp_l=1.8, bsfc=335.0, years="2008-2012, дефорс."),
}


# ======================================================================================
# 2. ГЕОМЕТРИЯ: маршрут = прямые + гибы (интегрирование по расписанию)
# ======================================================================================


def dir_from(yaw_deg, pitch_deg):
    y, p = math.radians(yaw_deg), math.radians(pitch_deg)
    return np.array([math.cos(p) * math.cos(y), math.cos(p) * math.sin(y), math.sin(p)])


def advance(q, yaw, pitch, item, R, per_bend):
    """Один шаг маршрута: возврат (новая точка, список точек, новый курс)."""
    if item[0] == "S":
        L = float(item[1])
        n = max(int(L / 40.0), 2)
        pts = [q + dir_from(yaw, pitch) * (L * k / n) for k in range(1, n + 1)]
        return q + dir_from(yaw, pitch) * L, pts, (yaw, pitch)
    ang, plane = float(item[1]), item[2]
    if abs(ang) < 0.05:
        return q, [], (yaw, pitch)
    arc = math.radians(abs(ang)) * R
    qp, pts = q.copy(), []
    for k in range(1, per_bend + 1):
        f = k / per_bend
        step = dir_from(yaw + math.radians(ang) * f, pitch) if plane == "yaw" \
            else dir_from(yaw, pitch + math.radians(ang) * f)
        qp = qp + step * (arc / per_bend)
        pts.append(qp.copy())
    yaw, pitch = (yaw + ang, pitch) if plane == "yaw" else (yaw, pitch + ang)
    return qp, pts, (yaw, pitch)


def integrate_route(start, yaw0, pitch0, sched, R, per_bend=24):
    """sched: [('S', len), ('B', angle_deg, 'yaw'|'pitch'), ('S?', 'x'|'y', target)].

    ('S?',…) — финальная прямая, длина решается так, чтобы дойти до плоскости x=/y=target.
    Возвращает centerline (N,3), [длины прямых], [информация о гибах].
    """
    q = np.array(start, dtype=float)
    yaw, pitch = float(yaw0), float(pitch0)
    sched = list(sched)
    for i in range(len(sched) - 1, -1, -1):
        if sched[i][0] == "S?":
            _, axis, target = sched[i]
            # переиграть маршрут до этого места, чтобы узнать курс/точку
            qq, yy, pp = q.copy(), yaw, pitch
            for it in sched[:i]:
                qq, _, (yy, pp) = advance(qq, yy, pp, it, R, per_bend)
            d = dir_from(yy, pp)
            idx = 0 if axis == "x" else 1
            L = 0.0 if abs(d[idx]) < 1e-6 else max(float((target - qq[idx]) / d[idx]), 0.0)
            sched[i] = ("S", round(L, 1))
            break
    cl, straights, bends = [q.copy()], [], []
    for item in sched:
        d0 = dir_from(yaw, pitch)
        q0 = q.copy()
        q, pts, (yaw, pitch) = advance(q, yaw, pitch, item, R, per_bend)
        cl.extend(pts)
        if item[0] == "S" and float(item[1]) > 0:
            straights.append(round(float(item[1]), 1))
        elif item[0] == "B" and abs(float(item[1])) >= 0.05:
            d1 = dir_from(yaw, pitch)
            ang = math.degrees(math.acos(float(np.clip(float(np.dot(d0, d1)), -1, 1))))
            bends.append(dict(angle=round(ang, 1), plane=item[2],
                              arc_len_mm=round(math.radians(abs(float(item[1]))) * R, 1),
                              R_mm=round(R, 1), x=round(float(q[0]), 0), y=round(float(q[1]), 0),
                              z=round(float(q[2]), 0), nominal=abs(float(item[1]))))
    return np.array(cl), straights, bends


def sweep_tube(cl, od, seg=24, caps=True):
    """Трубчатая поверхность вдоль centerline -> (verts, faces)."""
    cl = np.asarray(cl, dtype=float)
    r = od / 2.0
    t = np.gradient(cl, axis=0)
    t = t / np.maximum(np.linalg.norm(t, axis=1, keepdims=True), 1e-12)
    nb = np.cross(t, np.array([0.0, 0.0, 1.0]))
    bad = np.linalg.norm(nb, axis=1) < 1e-6
    if bad.any():
        nb[bad] = np.cross(t[bad], np.array([1.0, 0.0, 0.0]))
    nb = nb / np.maximum(np.linalg.norm(nb, axis=1, keepdims=True), 1e-12)
    bn = np.cross(t, nb)
    ang = np.linspace(0, 2 * np.pi, seg, endpoint=False)
    ca, sa = np.cos(ang), np.sin(ang)
    verts = np.concatenate([c + r * (np.outer(ca, nb[i]) + np.outer(sa, bn[i]))
                            for i, c in enumerate(cl)], axis=0)
    faces = []
    for i in range(len(cl) - 1):
        a0, a1 = i * seg, (i + 1) * seg
        for k in range(seg):
            u, v = k, (k + 1) % seg
            faces.append([a0 + u, a1 + u, a1 + v])
            faces.append([a0 + u, a1 + v, a0 + v])
    if caps:
        for side, ctr in ((0, cl[0]), (1, cl[-1])):
            ci = len(verts)
            verts = np.vstack([verts, ctr[None, :]])
            base = 0 if side == 0 else (len(cl) - 1) * seg
            for k in range(seg):
                u, v = k, (k + 1) % seg
                faces.append([ci, base + v, base + u] if side == 0 else [ci, base + u, base + v])
    return verts, np.array(faces)


def oval_prism(x0, y0, z0, L, W, H, ang_deg=0.0, seg=32):
    """Овальная (stadium) банка: сечение W×H в YZ, ось вдоль X, x0 — передний торец, z0 — центр."""
    ry, rz = W / 2.0, H / 2.0
    r = min(ry, rz)
    fw, fh = max(ry - r, 0.0), max(rz - r, 0.0)
    per = max(seg // 4, 8)
    prof = []
    for j, (cy, cz) in enumerate([(fw, fh), (-fw, fh), (-fw, -fh), (fw, -fh)]):
        for k in range(per + 1):
            a = math.radians(90 * j + 90 * k / per)
            prof.append((cy + r * math.cos(a), cz + r * math.sin(a)))
    prof = np.array(prof)
    n = len(prof)
    loc = np.vstack([np.column_stack([np.zeros(n), prof[:, 0], prof[:, 1]]),
                     np.column_stack([np.full(n, L), prof[:, 0], prof[:, 1]])])
    th = math.radians(ang_deg)
    Rz = np.array([[math.cos(th), -math.sin(th), 0.0],
                   [math.sin(th), math.cos(th), 0.0],
                   [0.0, 0.0, 1.0]])
    verts = loc @ Rz.T + np.array([x0, y0, z0])
    faces = []
    for i in range(n - 1):
        faces.append([i, n + i, n + i + 1])
        faces.append([i, n + i + 1, i + 1])
    for base, rev in ((0, True), (n, False)):
        c = verts[base:base + n].mean(axis=0)
        ci = len(verts)
        verts = np.vstack([verts, c[None, :]])
        for i in range(n - 1):
            faces.append([ci, base + i + 1, base + i] if rev else [ci, base + i, base + i + 1])
    return verts, np.array(faces)


def pt_rect_gap(points, x0, x1, y0, y1) -> float:
    pts = np.asarray(points)[:, :2]
    dx = np.maximum(np.maximum(x0 - pts[:, 0], pts[:, 0] - x1), 0.0)
    dy = np.maximum(np.maximum(y0 - pts[:, 1], pts[:, 1] - y1), 0.0)
    return float(np.sqrt(dx ** 2 + dy ** 2).min())


def rect_gap(a, b) -> float:
    dx = max(b[0] - a[1], a[0] - b[1], 0.0)
    dy = max(b[2] - a[3], a[2] - b[3], 0.0)
    return math.hypot(dx, dy)


def box_mass(p: Preset, wall_shell=1.2, core_wall=1.0) -> dict:
    """Масса банки: корпус (эллипс-периметр Рамануджана) + крышки + сердечник + перегородки + набивка."""
    a, b = p.box_w / 2.0, p.box_h / 2.0
    per = math.pi * (3 * (a + b) - math.sqrt((3 * a + b) * (a + 3 * b)))
    side, ends = per * p.box_l / 1e6, 2 * math.pi * a * b / 1e6
    shell = (side + ends) * wall_shell / 1000.0 * SS_RHO
    core = math.pi * p.core_id / 1000.0 * (0.8 * p.box_l / 1000.0) * core_wall / 1000.0 * SS_RHO
    baffle = ends * 1.5 / 1000.0 * SS_RHO
    pack = side * 0.5 * 0.115 + 0.3
    return dict(shell_kg=round(shell, 2), core_kg=round(0.55 * core, 2), baffle_kg=round(baffle, 2),
                packing_kg=round(pack, 2),
                total_kg=round(shell + 0.55 * core + baffle + pack + 0.3, 2),
                volume_l=round(math.pi * a * b * p.box_l / 1e9 * 1000 * 0.82, 2))


# ======================================================================================
# 3. ГИДРАВЛИКА / АКУСТИКА
# ======================================================================================


def gas_props(T, P):
    return P / (R_GAS * T), math.sqrt(GAMMA * R_GAS * T), 1.8e-5 * (T / 300.0) ** 0.76


def fric_dP(Q, D, L, T, P, rough=7e-5):
    """Дарси–Вейсбах, коэффициент трения по Colebrook."""
    if Q <= 0 or L <= 0:
        return 0.0, 0.0
    rho, c, mu = gas_props(T, P)
    A = math.pi * D ** 2 / 4
    v = Q / A
    Re = max(rho * v * D / mu, 1e3)
    f = 0.02
    for _ in range(14):
        f = (-2 * math.log10(max(rough / (3.7 * D) + 2.51 / (Re * math.sqrt(f)), 1e-12))) ** -2
    return f * (L / D) * rho * v * v / 2, v


def local_dP(K, Q, D, T, P):
    rho, c, mu = gas_props(T, P)
    v = Q / (math.pi * D ** 2 / 4)
    return K * rho * v * v / 2, v


def orifice_dP(Q, A_free, Cd, T, P):
    rho, c, mu = gas_props(T, P)
    v = Q / max(A_free, 1e-9)
    return rho * v * v / (2 * Cd ** 2), v


def air_mass_flow(engine, rpm):
    e = ENGINE[engine]
    ve = float(np.interp(rpm, [800, 1500, 2500, 3800, 5000, 6400], [.55, .74, .86, .92, .88, .78]))
    return e["disp_l"] * 1e-3 * ve * (101325 / (287.05 * 293.15)) * rpm / 120.0


def flow_model(d, engine):
    p = d.p
    L = {q.name: q.length for q in d.parts}
    n_bends = sum(len(q.bends) for q in d.parts if q.kind == "tube")
    Kb = 0.22 if d.r_factor >= 1.5 else 0.45
    core_id = (p.core_id - 2 * 1.0) / 1e3
    A_free = 0.30 * math.pi * core_id * (0.62 * p.box_l / 1000.0)
    rows = []
    for rpm in (1500, 2500, 3800, 5000, ENGINE[engine]["rpm_max"]):
        m_gas = air_mass_flow(engine, rpm) * 1.075
        T = float(np.interp(rpm, [800, 2000, 3800, 6400], [700, 820, 880, 850]))
        rho = gas_props(T, 101325.0)[0]
        Q, Qh = m_gas / rho, 0.5 * m_gas / rho
        det = [
            ("трение главной Ø%.1f" % p.main_od, *fric_dP(Q, (p.main_od - 2 * p.wall) / 1e3,
                                                          d.len_main / 1000.0, T, 101325.0)),
            (f"гибы {n_bends}×K{Kb}", *local_dP(Kb * max(1, n_bends), Q,
                                                (p.main_od - 2 * p.wall) / 1e3, T, 101325.0)),
            ("Y-развилка", *local_dP(0.35, Q, (p.main_od - 2 * p.wall) / 1e3, T, 101325.0)),
            ("трение ножек ×2", *fric_dP(Qh, (p.leg_od - 2 * p.wall) / 1e3,
                                         d.geo["leg_len_mm"]["L"] / 1000.0, T - 20, 102325.0)),
            ("перфорация сердечника", *orifice_dP(Qh, A_free, 0.62, T - 40, 104325.0)),
            ("поворот потока в банке K=1.2", *local_dP(1.2, Qh, core_id, T - 40, 103325.0)),
            ("трение хвостов", *fric_dP(Qh, (p.tail_od - 2 * p.wall) / 1e3,
                                        d.geo["tail_dev_mm"]["L"] / 1000.0, 520.0, 101325.0)),
            ("потеря выхода K=1", *local_dP(1.0, Qh, (p.tip_od - 2.0) / 1e3, 480.0, 101325.0)),
        ]
        if p.resonator:
            det.append(("резонатор K=0.25", *local_dP(0.25, Q, (p.res_d - 2.4) / 1e3, T, 101325.0)))
        dp = sum(x[1] for x in det)
        rows.append(dict(rpm=rpm, air_g_s=round(m_gas / 1.075 * 1000, 1), gas_g_s=round(m_gas * 1000, 1),
                         Q_hot_l_s=round(Q * 1000, 1), T_exit_K=round(T),
                         dp_kPa=round(dp / 1000, 2), dp_psi=round(dp / PSI, 3),
                         elements={k: [round(x / 1000, 2), round(v, 1)] for k, x, v in det}))

    def flow_at_dp(D_main, D_leg, n_leg, A_perf, L_tot, target=0.25 * PSI):
        lo, hi = 0.02, 1.2
        for _ in range(48):
            Q = 0.5 * (lo + hi)
            T = 850.0
            d1, _ = fric_dP(Q, D_main / 1e3, L_tot / 1000.0, T, 101325.0)
            d2, _ = local_dP(1.6, Q, D_main / 1e3, T, 101325.0)
            Qh = Q / max(n_leg, 1)
            d3, _ = orifice_dP(Qh, A_perf, 0.62, T - 40, 104325.0)
            d4, _ = fric_dP(Qh, D_leg / 1e3, 1.6, T - 40, 103325.0)
            if d1 + d2 + d3 + d4 > target:
                hi = Q
            else:
                lo = Q
        return 0.5 * (lo + hi)

    q_new = flow_at_dp(p.main_od - 2 * p.wall, p.leg_od - 2 * p.wall, 2, A_free * 2, d.len_main)
    q_stock = flow_at_dp(54.0, 54.0, 1, 0.30 * math.pi * 0.042 * (0.62 * 0.45), 3.9)
    Qred = max(r["Q_hot_l_s"] for r in rows) / 1000.0
    c_hot = gas_props(850.0, 103325.0)[1]
    vel = {}
    for lbl, D, share in (("главная", p.main_od, 1.0), ("ножка Y", p.leg_od, 0.5),
                          ("хвост", p.tail_od, 0.5), ("насадка", p.tip_od, 0.5),
                          ("сердечник банки", p.core_id, 0.5)):
        A = math.pi * (max(D - 2 * p.wall, 8.0) / 1e3) ** 2 / 4
        vel[lbl] = round(share * Qred / A, 1)
    return dict(rows=rows, velocity_at_max_flow_m_s=vel,
                mach_at_max_flow={k: round(v / c_hot, 2) for k, v in vel.items()},
                sound_speed_m_s=round(c_hot),
                flow_at_0p25psi_m3s=dict(design=round(q_new, 4), stock_like=round(q_stock, 4),
                                         gain_pct=round((q_new / q_stock - 1) * 100, 1)))


def acoustics(p, geo):
    c_hot = 500.0
    pulses = {rpm: 2 * rpm / 60 for rpm in (800, 1500, 2000, 2600, 3200, 3800, 5000)}
    L_tail = geo["tail_dev_mm"]["L"] / 1000.0
    f_qw, f_hw = c_hot / (4 * L_tail), c_hot / (2 * L_tail)
    hits = []
    for rpm, fp in pulses.items():
        for harm in (1, 2, 3):
            for mode, fx in (("1/4 волны хвоста", f_qw), ("1/2 волны хвоста", f_hw)):
                if abs(harm * fp - fx) / fx < 0.08:
                    hits.append(dict(rpm=rpm, harm=harm, f_excite=round(harm * fp, 1),
                                     f_pipe=round(fx, 1), mode=mode))
    freqs = [63, 125, 250, 500, 1000, 2000, 4000]
    S_box = 0.80 * p.box_w * p.box_h / 1e6
    S_pipe = math.pi * ((p.leg_od - 2 * p.wall) / 1e3) ** 2 / 4
    B = S_box / S_pipe

    def tl(L_eff):
        return {f: 10 * math.log10(1 + 0.25 * (B - 1 / B) ** 2
                                   * math.sin(2 * math.pi * f / c_hot * L_eff / 1e3 / 2) ** 2) for f in freqs}

    t1, t2 = tl(p.box_l), tl(0.55 * p.box_l)
    absorp = {63: 1, 125: 3, 250: 6, 500: 9, 1000: 11, 2000: 11, 4000: 9}
    # верхняя граница реактивного затухания режется: для bolt-on catback реальная вставка 5-15 дБ
    ins = {f: round(min(min(t1[f] + t2[f], 32) * 0.35 + absorp[f] * 0.75
                        + (3.0 if p.resonator else 0.0), 18.0), 1) for f in freqs}
    helm = {}
    for ft in (125.0, 155.0, 190.0):
        A = math.pi * 0.045 ** 2 / 4
        helm[int(ft)] = round((2 * math.pi * ft) ** 2 * A * 0.12 / c_hot ** 2 * 1000, 2)
    return dict(pulse_hz_by_rpm={k: round(v, 1) for k, v in pulses.items()},
                tail_len_mm=round(L_tail * 1000), f_quarter_wave_hz=round(f_qw, 1),
                f_half_wave_hz=round(f_hw, 1), drone_hits=hits,
                TL_box1_dB={int(k): round(v, 1) for k, v in t1.items()},
                TL_box2_dB={int(k): round(v, 1) for k, v in t2.items()},
                absorption_dB=absorp, insertion_loss_est_dB={int(k): v for k, v in ins.items()},
                box_volume_l=round(box_mass(p)["volume_l"], 2),
                helmholtz_volume_l=helm, expansion_ratio=round(B, 1))


# ======================================================================================
# 4. КОНСТРУКТОР ТРАССЫ
# ======================================================================================


@dataclass
class Part:
    name: str
    ru: str
    kind: str = "tube"
    cl: np.ndarray = None
    straights: list = field(default_factory=list)
    bends: list = field(default_factory=list)
    od: float = 0.0
    wall: float = 1.5
    meta: dict = field(default_factory=dict)
    meshes: list = field(default_factory=list)

    @property
    def length(self):
        return float(np.linalg.norm(np.diff(self.cl, axis=0), axis=1).sum()) if self.cl is not None else 0.0


class ExhaustDesign:
    def __init__(self, car: Car, preset: Preset, engine: str, tips_side="both", r_factor=1.6,
                 tip_in_bumper=False, box_bottom=255.0, tip_angle=22.5, box_in_x=2860.0,
                 box_off=0.0, z_line=330.0):
        self.car, self.p, self.engine = car, preset, engine
        self.r_factor, self.tips_side = r_factor, tips_side
        self.tip_in_bumper, self.tip_angle = tip_in_bumper, tip_angle
        self.box_bottom, self.box_in_x = box_bottom, box_in_x
        self.z_line = z_line
        # 0 = авто: «вразножку» банки подвинуть к краю (срез насадок красивее), «обе слева» — не надо
        self.box_off = box_off if box_off else (60.0 if tips_side == "both" else 0.0)
        self.parts, self.cut_rows, self.bend_rows = [], [], []
        self.warnings, self.geo = [], {}
        self.build()

    # ------------------------------------------------------------------ helpers
    def _route(self, name, ru, start, yaw0, pitch0, sched, od, wall=None, kind="tube"):
        R = self.r_factor * od
        cl, straights, bends = integrate_route(start, yaw0, pitch0, sched, R)
        if len(cl) < 2:
            raise ValueError(f"{name}: маршрут вырожден — гибы/прямые не влезают в доступное место")
        part = Part(name=name, ru=ru, kind=kind, cl=cl, straights=straights, bends=bends, od=od,
                    wall=wall if wall is not None else self.p.wall)
        part.meshes.append(sweep_tube(cl, od, seg=24))
        self.parts.append(part)
        lands = [s[1] for i, s in enumerate(sched) if s[0] == "S" and (
            i == 0 or i == len(sched) - 1 or sched[i - 1][0] == "B"
            or (i + 1 < len(sched) and sched[i + 1][0] == "B"))]
        min_land = min(lands) if lands else None
        for b in bends:
            self.bend_rows.append(dict(part=name, od_mm=round(od, 1), wall_mm=round(part.wall, 1),
                                       angle_deg=b["angle"], plane=b["plane"], radius_mm=round(R, 1),
                                       arc_len_mm=b["arc_len_mm"], x_at_mm=b["x"], y_at_mm=b["y"],
                                       z_at_mm=b["z"]))
            if min_land is not None and min_land < 25:
                self.warnings.append(f"{name}: полка {min_land:.0f} мм < 25 мм при гибе {b['angle']:.1f}° — "
                                      f"добавь прямую вставку или возьми угол из ряда покрупнее")
        self._cut(part, kind)
        return part

    def _cut(self, part: Part, kind="tube"):
        d_id = (part.od - 2 * part.wall) / 1000.0
        mass = part.length / 1000.0 * math.pi * ((part.od / 1000.0) ** 2 - d_id ** 2) / 4 * SS_RHO
        self.cut_rows.append(dict(
            part=part.name, name_ru=part.ru, kind=kind, od_mm=round(part.od, 1),
            wall_mm=round(part.wall, 1), id_mm=round(part.od - 2 * part.wall, 1),
            developed_len_mm=round(part.length, 1), n_straight=len(part.straights),
            straight_lens_mm="; ".join(f"{s:.0f}" for s in part.straights), n_bends=len(part.bends),
            bend_angles_deg="; ".join(f"{b['angle']:.1f}{'Y' if b['plane'] == 'yaw' else 'P'}"
                                      for b in part.bends),
            bend_radius_mm=round(self.r_factor * part.od, 1) if part.bends else "",
            mass_kg=round(mass, 2),
            x0=round(float(part.cl[0][0])), y0=round(float(part.cl[0][1])), z0=round(float(part.cl[0][2])),
            x1=round(float(part.cl[-1][0])), y1=round(float(part.cl[-1][1])), z1=round(float(part.cl[-1][2])),
            z_min=round(float(part.cl[:, 2].min() - part.od / 2), 1),
            z_max=round(float(part.cl[:, 2].max() + part.od / 2), 1)))

    # ------------------------------------------------------------------ трасса
    def build(self):
        p, car = self.p, self.car
        z = self.z_line
        z_box = self.box_bottom + p.box_h / 2.0
        box_in_x = self.box_in_x
        self.geo.update(z_line=z, z_box=z_box, box_in_x=box_in_x)

        # 1) приёмная: от штатного шарового за 2-м зондом; крен — «ступенька» из двух гибов 7.5°
        y_x = 1900.0
        res_x0 = y_x - 720.0 if p.resonator else y_x - 650.0
        res_x1 = res_x0 + (p.res_l if p.resonator else 0.0)
        oem = car.oem_outlet
        yaw_f = math.degrees(math.atan2(-oem[1], res_x0 - oem[0]))
        drop = oem[2] - z
        mid_crank = max(drop / math.tan(math.radians(7.5)), 60.0)
        front = self._route(
            "01_front",
            "Приёмная: от штатного шарового за 2-м лямбда-зондом (кат, гофра, оба зонда сохранены); "
            "ступенька по высоте %.0f мм гибами 7.5°" % drop,
            oem, yaw_f, 0.0,
            [("S", 220.0), ("B", -7.5, "pitch"), ("S", round(mid_crank, 1)), ("B", 7.5, "pitch"),
             ("S?", "x", res_x0)], p.main_od)
        y_main = float(front.cl[-1][1])
        self.geo["resonator"] = dict(x0=res_x0, x1=res_x1, d=p.res_d, on=p.resonator, z=z)

        # 2) резонатор (опция)
        if p.resonator:
            pr = Part("02_resonator", f"Резонатор Ø{p.res_d:.0f}×{p.res_l:.0f} сквозной, сердечник Ø42, "
                                      f"2 × Ø4 дренаж", kind="resonator", od=p.res_d, wall=1.2,
                      meta=dict(x0=res_x0, x1=res_x1, d=p.res_d, z=z, y=y_main))
            pr.cl = np.array([[res_x0, y_main, z], [res_x1, y_main, z]])
            pr.meshes.append(sweep_tube(pr.cl, p.res_d, seg=32))
            self.parts.append(pr)
            shell = math.pi * p.res_d / 1000 * p.res_l / 1000 * 1.2 / 1000 * SS_RHO
            self.cut_rows.append(dict(part=pr.name, name_ru=pr.ru, kind="resonator",
                                      od_mm=round(p.res_d, 1), wall_mm=1.2, id_mm=round(p.res_d - 2.4, 1),
                                      developed_len_mm=round(p.res_l, 1), n_straight=1,
                                      straight_lens_mm=f"{p.res_l:.0f}", n_bends=0, bend_angles_deg="",
                                      bend_radius_mm="", mass_kg=round(shell + 0.95, 2),
                                      x0=round(res_x0), y0=round(y_main), z0=round(z), x1=round(res_x1),
                                      y1=round(y_main), z1=round(z), z_min=round(z - p.res_d / 2, 1),
                                      z_max=round(z + p.res_d / 2, 1)))

        # 3) магистраль до Y — прямая
        self._route("03_mid", "Магистраль резонатор → Y (прямая)", (res_x1, y_main, z), 0.0, 0.0,
                    [("S", y_x - res_x1)], p.main_od)

        # 4) Y + ножки: режим насадок задаёт углы; центр банки = куда реально пришла труба
        crotch = (y_x, y_main, z)
        leg_ang = {"both": (22.5, -22.5), "left": (22.5, 37.5)}[self.tips_side]
        legs, y_c = {}, {}
        for side, ang in zip(("L", "R"), leg_ang):
            legs[side] = self._route(
                f"04_leg_{side}", f"Ножка Y {side}: гиб {abs(ang):.1f}° + прямая, вход в банку под углом",
                crotch, 0.0, 0.0, [("S", 200.0), ("B", ang, "yaw"), ("S?", "x", box_in_x)], p.leg_od)
            y_arr = float(legs[side].cl[-1][1])
            y_c[side] = round(y_arr + math.copysign(self.box_off, y_arr if abs(y_arr) > 1 else 1.0), 0)
        if self.tips_side == "left":
            need = p.box_w + 45.0
            if abs(y_c["R"] - y_c["L"]) < need:
                spread = (need - abs(y_c["R"] - y_c["L"])) / 2.0
                y_c["L"] = round(y_c["L"] - spread * (1.0 if y_c["L"] <= y_c["R"] else -1.0), 0)
                y_c["R"] = round(y_c["R"] + spread, 0)
        for side, leg in legs.items():
            arrive = float(leg.cl[-1][1])
            if abs(arrive - y_c[side]) > p.box_w / 2 - 20.0:
                self.warnings.append(f"{side}: труба приходит в y={arrive:.0f}, центр банки y={y_c[side]:.0f} "
                                     f"— вход вне морды; поправь --box-y на {arrive - y_c[side]:+.0f} мм "
                                     f"или угол ножки")
        self.geo["leg_len_mm"] = dict(L=round(legs["L"].length, 1), R=round(legs["R"].length, 1),
                                      delta=round(abs(legs["L"].length - legs["R"].length), 1))
        self.geo["box_y_by_side"] = y_c
        self.geo["leg_angles_deg"] = {"L": leg_ang[0], "R": leg_ang[1]}
        self.geo["y_branch"] = dict(kind="true-Y, 2×22.5°", inlet_od=p.main_od, leg_od=p.leg_od,
                                    x=y_x, z=z,
                                    note="вариант А: готовая штампованная Y %.0f→2×%.0f; вариант Б: отрезок "
                                         "Øглавной + 2 гиба 22.5° + косынки" % (p.main_od, p.leg_od))
        self.geo["box_inlet"] = {s: [round(float(v), 0) for v in legs[s].cl[-1]] for s in ("L", "R")}

        # 5) банки
        boxes = {}
        for side in ("L", "R"):
            bm = box_mass(p)
            y0c = float(y_c[side])
            bx = Part(f"05_box_{side}",
                      f"Банка {side}: овал {p.box_l:.0f}×{p.box_w:.0f}×{p.box_h:.0f}, сердечник "
                      f"Ø{p.core_id:.0f} (perf ≥30 %), 2 перегородки, набивка, вход Ø{p.leg_od:.0f} под 22.5°",
                      kind="box", od=max(p.box_w, p.box_h), wall=1.2,
                      meta=dict(x0=box_in_x, x1=box_in_x + p.box_l, y=y0c, z=z_box, L=p.box_l, W=p.box_w,
                                H=p.box_h, core=p.core_id, inlet_y=round(float(self.geo["box_inlet"][side][1]), 0),
                                **bm))
            bx.cl = np.array([[box_in_x, y0c, z_box], [box_in_x + p.box_l, y0c, z_box]])
            bx.meshes.append(oval_prism(box_in_x, y0c, z_box, p.box_l, p.box_w, p.box_h))
            self.parts.append(bx)
            self.cut_rows.append(dict(part=bx.name, name_ru=bx.ru, kind="box",
                                      od_mm=f"{p.box_l:.0f}x{p.box_w:.0f}x{p.box_h:.0f}", wall_mm=1.2,
                                      id_mm=f"core Ø{p.core_id:.0f}", developed_len_mm="", n_straight="",
                                      straight_lens_mm="", n_bends="", bend_angles_deg="завальцовка 2 шва",
                                      bend_radius_mm="", mass_kg=bm["total_kg"],
                                      x0=round(box_in_x), y0=round(y0c - p.box_w / 2),
                                      z0=round(z_box - p.box_h / 2), x1=round(box_in_x + p.box_l),
                                      y1=round(y0c + p.box_w / 2), z1=round(z_box + p.box_h / 2),
                                      z_min=round(z_box - p.box_h / 2, 1), z_max=round(z_box + p.box_h / 2, 1)))
            boxes[side] = dict(x0=box_in_x, x1=box_in_x + p.box_l, y=y0c, z=z_box)
        self.geo["boxes"] = boxes
        self.geo["box_y"] = abs(y_c["L"])

        # 6) хвосты + насадки
        tip_x = car.diff_x + (55.0 if not self.tip_in_bumper else -15.0)
        z_tip = z_box if not self.tip_in_bumper else 385.0
        tail_ang = {"both": (self.tip_angle, -self.tip_angle),
                    "left": (self.tip_angle * 0.5, self.tip_angle * 0.5)}[self.tips_side]
        tails = {}
        for side, sgn in (("L", 1.0), ("R", -1.0)):
            bx = boxes[side]
            out = {"L": -1.0, "R": 1.0} if self.tips_side == "left" else {"L": 1.0, "R": -1.0}
            y_out = bx["y"] + out[side] * (p.box_w / 2 - p.tail_od / 2 - 8.0)
            ang = tail_ang[0] if side == "L" else tail_ang[1]
            if self.tips_side == "left":
                ang = abs(ang)
            tails[side] = self._route(f"06_tail_{side}", f"Хвост {side} Ø{p.tail_od:.0f} (гиб {abs(ang):.1f}° к краю)",
                                       (bx["x1"], y_out, bx["z"]), 0.0, 0.0,
                                       [("S", 60.0), ("B", ang, "yaw"), ("S?", "x", tip_x - 190.0)], p.tail_od)
        self.geo["tail_dev_mm"] = {k: round(v.length, 1) for k, v in tails.items()}
        for side in ("L", "R"):
            t = tails[side]
            st = np.array(t.cl[-1])
            d1 = t.cl[-1] - t.cl[-2]
            self._route(f"07_tip_{side}",
                        f"Насадка Ø{p.tip_od:.0f} "
                        f"({'из-под бампера' if not self.tip_in_bumper else 'в окне бампера'})",
                        tuple(st), math.degrees(math.atan2(d1[1], d1[0])), 0.5,
                        [("S", tip_x - float(st[0]))], p.tip_od, wall=1.0)
        self.geo["tips"] = dict(od=p.tip_od, side=self.tips_side, x_end=tip_x, z=round(z_tip, 0),
                                y_L=round(float(self.parts[-2].cl[-1][1]), 0),
                                y_R=round(float(self.parts[-1].cl[-1][1]), 0),
                                in_bumper=self.tip_in_bumper, angle_deg=self.tip_angle)

        # 7) подвесы
        self.geo["hangers"] = [
            dict(id="H1", x=300, y=round(float(front.cl[2][1]), 0), z=round(z + 55),
                 type="штатная резина Ø14", note="проушина на приёмной трубе — не переваривать"),
            dict(id="H2", x=round(res_x0 - 90), y=round(y_main), z=round(z + 55), type="штатная резина Ø14",
                 note="проушина тоннеля перед резонатором"),
            dict(id="H3", x=round(y_x - 80), y=round(y_main), z=round(z + 55), type="штатная резина Ø14",
                 note="перед Y: снять вес развилки с приёмной трубы"),
            dict(id="H4L", x=round(box_in_x + 60), y=float(y_c["L"]), z=round(z_box + p.box_h / 2 + 25),
                 type="новый кронштейн + резина Ø14", note="варить к усилителю порога, шов 30 мм"),
            dict(id="H4R", x=round(box_in_x + 60), y=float(y_c["R"]), z=round(z_box + p.box_h / 2 + 25),
                 type="новый кронштейн + резина Ø14", note="зеркально H4L"),
            dict(id="H5L", x=round(box_in_x + p.box_l - 40), y=float(y_c["L"]), z=round(z_box + p.box_h / 2 + 25),
                 type="новый кронштейн + резина Ø12", note="разгрузка шва банки на хвосте"),
            dict(id="H5R", x=round(box_in_x + p.box_l - 40), y=float(y_c["R"]), z=round(z_box + p.box_h / 2 + 25),
                 type="новый кронштейн + резина Ø12", note="зеркально H5L"),
            dict(id="H6", x=round(res_x0 + p.res_l / 2), y=0,
                 z=round(z + (p.res_d if p.resonator else p.main_od) / 2 + 30), type="хомут 1 шт",
                 note="резонатор не должен висеть на трубе")]
        self.geo["drains"] = dict(diam_mm=4.5, per_box=1,
                                  pos="низ задней кромки банки, 25 мм от вертикальной оси")
        self.geo["heat_shields"] = ["участок главной под баком (x 1900-2700) — экран 0.8 мм",
                                    "Y-развилка, если до пола <60 мм — экран",
                                    "над тормозной магистралью — экран, варить только через экран"]
        self.len_main = sum(q.length for q in self.parts if q.name in ("01_front", "03_mid"))
        self.geo["pipe_len_mm"] = round(sum(float(r["developed_len_mm"]) for r in self.cut_rows
                                            if r["developed_len_mm"] != ""), 0)
        self.clearances()
        self.bom()

    # ------------------------------------------------------------------ зазоры / BOM
    def clearances(self):
        car, p = self.car, self.p
        allv = np.vstack([v for part in self.parts for (v, f) in part.meshes])
        i_low = int(np.argmin(allv[:, 2]))
        c = dict(ground_min_mm=round(float(allv[i_low, 2]), 1),
                 lowest_point_x_mm=round(float(allv[i_low, 0])),
                 floor_gap_rear_mm=round(car.floor_rear - (self.geo["z_box"] + p.box_h / 2), 1))
        tank = (car.tank_x0, car.tank_x1, -car.tank_y, car.tank_y)
        beam = (car.axle_x - car.beam_x, car.axle_x + car.beam_x, -700.0, 700.0)
        hitch = (car.hitch_x, car.diff_x + 40, -320.0, 320.0)
        bxL = self.geo["boxes"]["L"]
        boxp = (bxL["x0"], bxL["x1"], bxL["y"] - p.box_w / 2, bxL["y"] + p.box_w / 2)
        c["tank_gap_mm"] = round(rect_gap(boxp, tank), 1)
        c["tank_x_overlap"] = bool(boxp[0] < car.tank_x1 and boxp[1] > car.tank_x0)
        under = allv[(allv[:, 0] > car.tank_x0) & (allv[:, 0] < car.tank_x1) & (np.abs(allv[:, 1]) < car.tank_y)]
        c["tank_bottom_gap_mm"] = round(float(car.tank_bottom - under[:, 2].max()), 1) if len(under) else None
        c["tank_plan_cross"] = bool(len(under))
        c["beam_gap_mm"] = round(rect_gap(boxp, beam), 1)
        c["beam_note"] = "банка вне зоны балки" if c["beam_gap_mm"] > 0 else \
            "ПЕРЕСЕЧЕНИЕ с зоной торсиона — увести банку вперёд/вверх"
        c["hitch_zone_gap_mm"] = round(pt_rect_gap(allv, *hitch), 1)
        sel = allv[(allv[:, 0] > car.brake_x0) & (allv[:, 0] < car.brake_x1)]
        c["brake_line_gap_mm"] = round(float(np.abs(np.abs(sel[:, 1]) - car.brake_line_y).min())
                                       if len(sel) else 999.0, 1)
        c["wheelhouse_gap_mm"] = round(car.wheelhouse_y - float(np.abs(allv[:, 1]).max()), 1)
        c["rear_bumper_protrusion_mm"] = round(float(allv[:, 0].max() - car.diff_x), 1)
        c["lateral_span_mm"] = round(2 * float(np.abs(allv[:, 1]).max()), 1)
        behind = allv[allv[:, 0] > car.axle_x]
        c["departure_angle_deg"] = round(math.degrees(math.atan2(
            float(behind[:, 2].min()), max(float(behind[:, 0].max() - car.axle_x), 1.0))), 1)
        for k, lim, msg in [
            ("tank_bottom_gap_mm", 40, "менее 40 мм до низа бака — ищи штатную канавку под трубу"),
            ("ground_min_mm", 250, "низ системы ниже 250 мм — чиркнет на горке/погрузке"),
            ("floor_gap_rear_mm", 30, "банка ближе 30 мм к полу — дребезг/промятие"),
            ("tank_gap_mm", 40, "менее 40 мм до бака — теплоэкран обязателен, сварка рядом запрещена"),
            ("beam_gap_mm", 25, "менее 25 мм до зоны балки — проверь ход подвески"),
            ("brake_line_gap_mm", 40, "менее 40 мм до тормозной магистрали"),
            ("hitch_zone_gap_mm", 20, "в зоне кронштейна фаркопа — проверь «язык»/замок"),
            ("wheelhouse_gap_mm", 25, "менее 25 мм до арки")]:
            v = c.get(k)
            if v is not None and v < lim:
                self.warnings.append(f"зазор {k}={v:.0f} мм: {msg}")
        if c["rear_bumper_protrusion_mm"] > 90:
            self.warnings.append(f"вылет за бампер {c['rear_bumper_protrusion_mm']:.0f} мм > 90 — острый зацеп, "
                                 f"вопросы на техосмотре")
        self.geo["clearance"] = c

    def bom(self):
        p = self.p
        raw = [
            dict(item="Труба Ø%.1f×%.1f AISI 304L" % (p.main_od, p.wall), qty="1 хлыст 6 м",
                 note="главная + запас на перемычки"),
            dict(item="Труба Ø%.1f×%.1f AISI 304L" % (p.leg_od, p.wall), qty="2 м", note="ножки Y (×2)"),
            dict(item="Труба Ø%.1f×%.1f AISI 304L" % (p.tail_od, p.wall), qty="1.5 м", note="хвосты (×2)"),
            dict(item="Труба Ø%.1f×1.0 AISI 304L" % p.tip_od, qty="0.6 м", note="насадки (×2)"),
            dict(item="Заготовка банки %.0f×%.0f×%.0f, корпус 1.2 / крышка 1.5" % (p.box_l, p.box_w, p.box_h),
                 qty="2 шт", note="или готовая банка с сердечником"),
            dict(item=f"Труба перфорированная Ø{p.core_id:.0f}, шаг 8, живое сечение ≥30 %", qty="1.8 м",
                 note="сердечники 2 банок + резонатор"),
            dict(item="Набивка базальт/стекловолокно 100-128 кг/м³", qty="~1.6 кг",
                 note="по 2 слоя, не трамбовать"),
            dict(item="Гиб мандрел, угол 7.5-45°", qty=f"{len(self.bend_rows)} шт", note="см. bends.csv"),
            dict(item="Хомут нерж. 12 мм под болт M8", qty="6 шт",
                 note="стык шаровой + 2 банки + резонатор + 2 хвоста"),
            dict(item="Прокладка шарового соединения", qty="1 шт", note="или ремкомплект шарового"),
            dict(item="Резиновый подвес Ø14 / Ø12", qty="4 + 4 шт", note="3 штатных точки + новые"),
            dict(item="Кронштейн под банку (полоса 25×4)", qty="4 шт", note="под новые точки H4/H5"),
            dict(item="Экран теплоотражающий 0.8 мм алюм.", qty="0.5 м²", note="над баком и тормозной трубкой"),
            dict(item="Дренаж", qty="3 отв. Ø4.5", note="банка L/R + резонатор"),
        ]
        merged = {}
        for it in raw:
            k = it["item"]
            if k in merged:
                merged[k]["qty"] = f'{merged[k]["qty"]} + {it["qty"]}'
                merged[k]["note"] = (merged[k]["note"] + "; " + it["note"])[:140]
            else:
                merged[k] = dict(it)
        self.geo["bom"] = list(merged.values())


# ======================================================================================
# 5. ЭКСПОРТ
# ======================================================================================

COLRGB = {"tube": (154, 164, 173, 255), "box": (192, 122, 78, 255), "tip": (222, 225, 230, 255),
          "resonator": (127, 169, 140, 255)}


def export_meshes(d, outdir):
    import trimesh
    d3 = os.path.join(outdir, "3d")
    os.makedirs(d3, exist_ok=True)
    geoms = []
    for part in d.parts:
        vs, fs, off = [], [], 0
        for (v, f) in part.meshes:
            vs.append(v); fs.append(f + off); off += len(v)
        m = trimesh.Trimesh(vertices=np.vstack(vs), faces=np.vstack(fs), process=False)
        m.visual.face_colors = np.tile(COLRGB.get(part.kind, COLRGB["tube"]), (len(m.faces), 1))
        geoms.append((part.name, m))
        m.export(os.path.join(d3, f"{part.name}.stl"))
    allv = np.vstack([m.vertices for _, m in geoms])
    allf, off = [], 0
    for _, m in geoms:
        allf.append(m.faces + off); off += len(m.vertices)
    allf = np.vstack(allf)
    trimesh.Trimesh(vertices=allv, faces=allf, process=False).export(
        os.path.join(d3, "astra_h_dual_exhaust_assembly.stl"))
    sc = trimesh.Scene()
    for name, m in geoms:
        sc.add_geometry(m, geom_name=name)
    sc.export(os.path.join(d3, "astra_h_dual_exhaust.glb"))
    bb = allv.max(axis=0) - allv.min(axis=0)
    return dict(n_parts=len(geoms), total_faces=int(len(allf)), bbox_mm=[round(float(x), 1) for x in bb])


def export_scad(d, car: Car, path):
    p = d.p
    L = [f"// Astra H 1.8 Caravan — раздвоенная трасса, пресет {p.name} (сгенерировано)",
         "// единицы мм; X=0 ось передних колёс (+X назад), Y + влево, Z от земли",
         "$fa=6; $fs=1.0;",
         f"MAIN_OD={p.main_od}; LEG_OD={p.leg_od}; TAIL_OD={p.tail_od}; TIP_OD={p.tip_od};",
         f"WALL={p.wall}; BOX_L={p.box_l}; BOX_W={p.box_w}; BOX_H={p.box_h}; CORE_OD={p.core_id};",
         f"BEND_R={d.r_factor * p.main_od:.1f};"]
    for part in d.parts:
        if part.kind == "tube":
            pts = ", ".join(f"[{x:.1f},{y:.1f},{z:.1f}]" for x, y, z in part.cl)
            L.append(f"route_{part.name} = [{pts}];  // {part.ru} | развертка {part.length:.0f} мм")
    L += ["", "module sweep3d(pts, d) {", "  n = len(pts);", "  for (i = [0:n-2]) {",
          "    a = pts[i]; b = pts[i+1]; v = b - a; h = norm(v);",
          "    if (h > 0.001) {",
          "      ang = atan2(sqrt(v.x*v.x + v.y*v.y), v.z);",
          "      rot = atan2(v.y, v.x) + 90;",
          "      translate(a) rotate([0, ang, rot]) cylinder(h=h, d=d, $fn=48);",
          "    }", "  }", "}",
          "module oval_can(x0, y0, z0, L, W, H) {",
          "  translate([x0 + L/2, y0, z0]) rotate([0, 90, 0])",
          "    scale([1, 1, W/H]) cylinder(h=L, d=H, center=true, $fn=64);", "}",
          f"module car_ref() {{ %color(\"grey\") translate([{-car.front_overhang:.0f},"
          f"{-car.width / 2:.0f},300])",
          f"  cube([{car.length:.0f},{car.width:.0f},{car.floor_rear + 60 - 300:.0f}]); }}", "", "car_ref();"]
    for part in d.parts:
        if part.kind == "tube":
            ds = {"01_front": "MAIN_OD", "03_mid": "MAIN_OD"}.get(
                part.name, "LEG_OD" if "leg" in part.name else "TAIL_OD" if "tail" in part.name else "TIP_OD")
            L.append(f"sweep3d(route_{part.name}, {ds});")
        elif part.kind == "box":
            m = part.meta
            L.append(f'color("IndianRed") oval_can({m["x0"]:.0f},{m["y"]:.0f},{m["z"]:.0f}, '
                     f"BOX_L, BOX_W, BOX_H);")
        elif part.kind == "resonator":
            m = part.meta
            L.append(f'color("SeaGreen") translate([{m["x0"]:.0f},{m["y"]:.0f},{m["z"]:.0f}]) '
                     f'rotate([0,90,0]) cylinder(h={m["x1"] - m["x0"]:.0f}, d={m["d"]:.0f}, $fn=48);')
    with open(path, "w", encoding="utf-8") as fh:
        fh.write("\n".join(L) + "\n")


def render_png(d, car: Car, outdir):
    import matplotlib
    matplotlib.use("Agg")
    import matplotlib.pyplot as plt
    import matplotlib.colors as mcolors
    from mpl_toolkits.mplot3d.art3d import Poly3DCollection
    os.makedirs(outdir, exist_ok=True)
    hexc = {"tube": "#9aa4ad", "box": "#c07a4e", "tip": "#dfe3e8", "resonator": "#7fa98c"}
    light = np.array([0.35, 0.45, 0.82]); light = light / np.linalg.norm(light)

    def draw_system(ax):
        for part in d.parts:
            col = np.array(mcolors.to_rgb(hexc.get(part.kind, "#999")))
            for (v, f) in part.meshes:
                tri = v[f]
                nn = np.cross(tri[:, 1] - tri[:, 0], tri[:, 2] - tri[:, 0])
                nn = nn / np.maximum(np.linalg.norm(nn, axis=1, keepdims=True), 1e-9)
                sh = 0.3 + 0.7 * np.clip(nn @ light, 0, 1)
                ax.add_collection3d(Poly3DCollection(tri, facecolors=np.clip(col[None, :] * sh[:, None], 0, 1),
                                                     edgecolors="none"))

    def draw_context(ax, alpha=.10):
        X0, X1, W = -car.front_overhang, car.diff_x, car.width / 2
        z0, z1 = 250.0, car.floor_rear + 40
        quads = [[(X0, -W, z0), (X1, -W, z0), (X1, W, z0), (X0, W, z0)],
                 [(X0, -W, z1), (X1, -W, z1), (X1, W, z1), (X0, W, z1)]]
        for y in (-W, W):
            quads.append([(X0, y, z0), (X1, y, z0), (X1, y, z1), (X0, y, z1)])
        for q in quads:
            ax.add_collection3d(Poly3DCollection([np.array(q)], facecolors="#9fb2c8", alpha=alpha,
                                                 edgecolors="#7c8ba0", linewidths=.6))
        t = (car.tank_x0, car.tank_x1)
        ax.add_collection3d(Poly3DCollection([np.array([(x, y, z) for x, y, z in [
            (t[0], -car.tank_y, car.tank_bottom), (t[1], -car.tank_y, car.tank_bottom),
            (t[1], car.tank_y, car.tank_bottom), (t[0], car.tank_y, car.tank_bottom),
            (t[0], -car.tank_y, car.tank_bottom + car.tank_h), (t[1], -car.tank_y, car.tank_bottom + car.tank_h),
            (t[1], car.tank_y, car.tank_bottom + car.tank_h), (t[0], car.tank_y, car.tank_bottom + car.tank_h)]])],
            facecolors="#2a7", alpha=.16, edgecolors="#1c6b45", linewidths=.8))
        th = np.linspace(0, 2 * np.pi, 40)
        for wx, tr in ((0, car.track_front), (car.wheelbase, car.track_rear)):
            for wy in (-tr / 2, tr / 2):
                ax.plot(wx + 312 * np.cos(th), np.full(40, wy), 312 + 312 * np.sin(th),
                        color="#333", lw=1.3, alpha=.85)

    # ---------- 3D: 3/4 снизу-сзади + вид сзади
    fig = plt.figure(figsize=(15, 7.6), dpi=118)
    ax = fig.add_subplot(1, 2, 1, projection="3d", computed_zorder=False)
    draw_context(ax); draw_system(ax)
    ax.set_xlim(-1000, 3800); ax.set_ylim(-900, 900); ax.set_zlim(120, 620)
    try:
        ax.set_box_aspect((4800, 1800, 500))
    except Exception:
        pass
    ax.view_init(elev=16, azim=-143)
    ax.set_xlabel("X, мм (0 = ось передних колёс)"); ax.set_ylabel("Y, мм (+ влево)"); ax.set_zlabel("Z, мм")
    ax.set_title(f"Вид 3/4 снизу-сзади — «{d.p.name}»: Ø{d.p.main_od:.1f} → Y → 2×Ø{d.p.leg_od:.0f} → "
                 f"2 банки {d.p.box_l:.0f}×{d.p.box_w:.0f}×{d.p.box_h:.0f} → 2×Ø{d.p.tip_od:.0f}", fontsize=10)
    ax.grid(alpha=.18)
    ax2 = fig.add_subplot(1, 2, 2, projection="3d", computed_zorder=False)
    draw_context(ax2); draw_system(ax2)
    ax2.view_init(elev=11, azim=-90)
    ax2.set_xlim(2300, 3800); ax2.set_ylim(-900, 900); ax2.set_zlim(120, 700)
    try:
        ax2.set_box_aspect((1500, 1800, 580))
    except Exception:
        pass
    ax2.set_title("Вид сзади: постановка насадок (y = ±%.0f мм), угол съезда %.1f°"
                  % (abs(d.geo["tips"]["y_L"]), d.geo["clearance"]["departure_angle_deg"]), fontsize=10)
    ax2.set_xlabel("X"); ax2.set_ylabel("Y, мм"); ax2.set_zlabel("Z, мм"); ax2.grid(alpha=.18)
    fig.tight_layout(); fig.savefig(os.path.join(outdir, "render_3d_iso.png")); plt.close(fig)

    # ---------- side + top
    fig, (ax_s, ax_t) = plt.subplots(2, 1, figsize=(15, 11), dpi=118)
    wr, FB = 0.312, -car.front_overhang / 1000
    body = [(FB, .32), (FB + .03, .78), (-.30, .88), (.10, .93), (.62, 1.47), (3.32, 1.47),
            (3.55, 1.12), (car.diff_x / 1000, 1.00), (car.diff_x / 1000, .30)]
    ax_s.plot([b[0] for b in body], [b[1] for b in body], color="#333", lw=1.0)
    for wx, tr in ((0, car.track_front), (car.wheelbase, car.track_rear)):
        ax_s.add_patch(plt.Circle((wx / 1000, wr), wr, fill=False, color="#333", lw=1.2))
        ax_s.add_patch(plt.Circle((wx / 1000, wr), .13, fill=False, color="#bbb", lw=.8))
    ax_s.axhline(car.floor_rear / 1000, color="#888", ls=":", lw=1)
    ax_s.text(FB + .02, car.floor_rear / 1000 + .02, "низ пола кузова", color="#777", fontsize=8)
    ax_s.add_patch(plt.Rectangle((car.tank_x0 / 1000, car.tank_bottom / 1000),
                                 (car.tank_x1 - car.tank_x0) / 1000, car.tank_h / 1000,
                                 color="#2a7", alpha=.28, label="топливный бак (низ/верх)"))
    ax_s.text(car.tank_x0 / 1000 + .02, (car.tank_bottom + car.tank_h / 2) / 1000,
              "бак: трасса проходит ПОД ним", fontsize=8, color="#1c6b45")
    ax_s.add_patch(plt.Rectangle(((car.axle_x - car.beam_x) / 1000, .30), 2 * car.beam_x / 1000,
                                 (car.beam_top - 300) / 1000, color="#a33", alpha=.3, label="зона торсиона"))
    ax_t.add_patch(plt.Rectangle((FB, -car.width / 2000), car.length / 1000, car.width / 1000,
                                 fill=False, color="#333", lw=1.0))
    ax_t.add_patch(plt.Rectangle((car.tank_x0 / 1000, -car.tank_y / 1000),
                                 (car.tank_x1 - car.tank_x0) / 1000, 2 * car.tank_y / 1000,
                                 color="#2a7", alpha=.24, label="бак"))
    for by in (car.brake_line_y, -car.brake_line_y):
        ax_t.plot([car.brake_x0 / 1000, car.brake_x1 / 1000], [by / 1000, by / 1000], color="#b80",
                  lw=1.3, ls="-.", label="тормозная магистраль" if by > 0 else None)
    for wx, tr in ((0, car.track_front), (car.wheelbase, car.track_rear)):
        for sgn in (-1, 1):
            ax_t.add_patch(plt.Rectangle((wx / 1000 - .115, sgn * car.width / 2000 - (.245 if sgn > 0 else 0)),
                                         .23, .245, fill=False, color="#333", lw=1))
    for side in ("L", "R"):
        b = d.geo["boxes"][side]
        ax_t.add_patch(plt.Rectangle((b["x0"] / 1000, (b["y"] - d.p.box_w / 2) / 1000), d.p.box_l / 1000,
                                     d.p.box_w / 1000, color="#c07a4e", alpha=.35,
                                     label=f"банка {side}" if side == "L" else None))
        ax_s.add_patch(plt.Rectangle((b["x0"] / 1000, (b["z"] - d.p.box_h / 2) / 1000), d.p.box_l / 1000,
                                     d.p.box_h / 1000, color="#c07a4e", alpha=.35))
    for part in d.parts:
        if part.cl is None:
            continue
        cl = part.cl / 1000.0
        col, lw = hexc.get(part.kind, "#555"), (3.0 if part.kind in ("tube", "tip") else 6.5)
        for a, arr in ((ax_s, cl[:, [0, 2]]), (ax_t, cl[:, [0, 1]])):
            a.plot(arr[:, 0], arr[:, 1], color=col, lw=lw, solid_capstyle="round", zorder=5)
    for h in d.geo["hangers"]:
        ax_s.plot(h["x"] / 1000, h["z"] / 1000, marker="^", color="#08a", ms=8, zorder=6)
        ax_t.plot(h["x"] / 1000, h["y"] / 1000, marker="^", color="#08a", ms=8, zorder=6)
    for a, yl, lab in ((ax_s, (-150, 1750), "Z, м"), (ax_t, (-1080, 1080), "Y, м")):
        a.set_xlim(-1.15, 4.05); a.set_ylim([v / 1000 for v in yl]); a.set_aspect("equal")
        a.grid(alpha=.3); a.set_xlabel("X, м  (0 — ось передних колёс)"); a.set_ylabel(lab)
    ax_s.set_title("Вид сбоку; ▲ — точки подвеса", fontsize=11); ax_s.legend(loc="center right", fontsize=8)
    ax_t.set_title("Вид сверху (+Y — левый борт)", fontsize=11); ax_t.legend(loc="center right", fontsize=8)
    fig.tight_layout(); fig.savefig(os.path.join(outdir, "drawing_side_top.png")); plt.close(fig)

    # ---------- схема гибов
    fig, ax = plt.subplots(figsize=(13, 6.4), dpi=118)
    for part in d.parts:
        if part.cl is None or part.kind == "box":
            continue
        cl = part.cl / 1000.0
        ax.plot(cl[:, 0], cl[:, 1], color="#7f8c8d", lw=2.2, solid_capstyle="round")
        for i, b in enumerate(part.bends, 1):
            ax.plot(b["x"] / 1000, b["y"] / 1000, marker="o", ms=7, mfc="#c0392b", mec="w", zorder=5)
            ax.annotate(f"B{i} {b['angle']:.1f}°{b['plane']} R{b['R_mm']:.0f}", (b["x"] / 1000, b["y"] / 1000),
                        fontsize=7.5, xytext=(4, 6), textcoords="offset points", color="#c0392b")
    for s in d.geo["hangers"]:
        ax.text(s["x"] / 1000, s["y"] / 1000 - .12, s["id"], fontsize=7.5, color="#08a", ha="center")
    ax.set_aspect("equal"); ax.grid(alpha=.3); ax.set_xlim(-1.2, 4.0); ax.set_ylim(-1.05, 1.05)
    ax.set_title("План трассы с номерами гибов (yaw — в плане, pitch — наклон); красный = гиб из ряда", fontsize=11)
    ax.set_xlabel("X, м"); ax.set_ylabel("Y, м")
    fig.tight_layout(); fig.savefig(os.path.join(outdir, "bends.png")); plt.close(fig)

    # ---------- зазоры
    c = d.geo["clearance"]
    items = [("ground_min_mm", "низ системы → дорога", 250, "мм"),
             ("floor_gap_rear_mm", "банка → пол кузова", 30, "мм"),
             ("tank_bottom_gap_mm", "трасса → низ бака (по Z)", 40, "мм"),
             ("tank_gap_mm", "банка → бак (план)", 40, "мм"),
             ("beam_gap_mm", "банка → зона балки", 25, "мм"),
             ("brake_line_gap_mm", "система → тормозная магистраль", 40, "мм"),
             ("hitch_zone_gap_mm", "система → зона фаркопа", 20, "мм"),
             ("wheelhouse_gap_mm", "система → арка", 25, "мм"),
             ("rear_bumper_protrusion_mm", "вылет за бампер", None, "мм"),
             ("departure_angle_deg", "угол съезда", 14.0, "°")]
    ys = np.arange(len(items))[::-1]
    vals, bars, cols = [], [], []
    for (k, lbl, lim, unit), y in zip(items, ys):
        v = c.get(k); v = 0.0 if v is None else float(v)
        vals.append((k, lbl, v, unit, lim)); bars.append(abs(v))
        good = (0 <= v <= 90) if k == "rear_bumper_protrusion_mm" else (True if lim is None else v >= lim)
        cols.append("#178f4e" if good else "#c0392b")
    fig, ax = plt.subplots(figsize=(13, 6.2), dpi=118)
    ax.barh(ys, bars, color=cols, alpha=.85)
    for y, (k, lbl, v, unit, lim) in zip(ys, vals):
        note = "0…90 мм" if k == "rear_bumper_protrusion_mm" else (f"норма ≥{lim:g}" if lim else "—")
        ax.text(abs(v) + max(bars) * .012, y, f"{v:.0f} {unit}   ({note})", va="center", fontsize=9)
    ax.set_yticks(ys); ax.set_yticklabels([v[1] for v in vals], fontsize=9)
    ax.set_xlim(0, max(bars) * 1.8)
    ax.set_title("Зазоры проекта — номинал. Перед резкой: примерка и обмер конкретной машины", fontsize=11)
    ax.set_xlabel("мм (кроме угла съезда)"); ax.grid(axis="x", alpha=.3)
    fig.tight_layout(); fig.savefig(os.path.join(outdir, "clearances.png")); plt.close(fig)


def export_dxf(d, car: Car, path):
    import ezdxf
    doc = ezdxf.new("R2010", setup=True)
    doc.header["$INSUNITS"] = 4
    for lay, col in (("PIPE", 3), ("BOX", 1), ("CAR", 8), ("DIM", 5), ("HANGER", 6),
                     ("TEXT", 7), ("RES", 4), ("BEND", 2)):
        if lay not in doc.layers:
            doc.layers.add(lay, color=col)
    geo, p = d.geo, d.p
    FB = -car.front_overhang
    for view in ("SIDE", "TOP"):
        msp = doc.modelspace()
        if view == "SIDE":
            msp.add_lwpolyline([(FB, 320), (FB + 30, 780), (585, 880), (985, 930), (1505, 1470),
                                (3320, 1470), (3550, 1120), (car.diff_x, 1000), (car.diff_x, 300)],
                               close=True, dxfattribs=dict(layer="CAR"))
            for wx in (0, car.wheelbase):
                msp.add_circle((wx, 312), 312, dxfattribs=dict(layer="CAR"))
                msp.add_circle((wx, 312), 130, dxfattribs=dict(layer="CAR"))
            msp.add_line((FB, car.floor_rear), (car.diff_x, car.floor_rear), dxfattribs=dict(layer="CAR"))
            msp.add_lwpolyline([(car.tank_x0, car.tank_bottom), (car.tank_x1, car.tank_bottom),
                                (car.tank_x1, car.tank_bottom + car.tank_h), (car.tank_x0, car.tank_bottom + car.tank_h)],
                               close=True, dxfattribs=dict(layer="CAR"))
            msp.add_lwpolyline([(car.axle_x - car.beam_x, 300), (car.axle_x + car.beam_x, 300),
                                (car.axle_x + car.beam_x, car.beam_top), (car.axle_x - car.beam_x, car.beam_top)],
                               close=True, dxfattribs=dict(layer="CAR"))
        else:
            msp.add_lwpolyline([(FB, -car.width / 2), (car.diff_x, -car.width / 2), (car.diff_x, car.width / 2),
                                (FB, car.width / 2)], close=True, dxfattribs=dict(layer="CAR"))
            msp.add_lwpolyline([(car.tank_x0, -car.tank_y), (car.tank_x1, -car.tank_y),
                                (car.tank_x1, car.tank_y), (car.tank_x0, car.tank_y)], close=True,
                               dxfattribs=dict(layer="CAR"))
            for by in (car.brake_line_y, -car.brake_line_y):
                msp.add_line((car.brake_x0, by), (car.brake_x1, by), dxfattribs=dict(layer="CAR"))
            for wx in (0, car.wheelbase):
                for sgn in (-1, 1):
                    msp.add_lwpolyline([(wx - 115, sgn * car.width / 2), (wx + 115, sgn * car.width / 2),
                                        (wx + 115, sgn * (car.width / 2 - 245)),
                                        (wx - 115, sgn * (car.width / 2 - 245))], close=True,
                                       dxfattribs=dict(layer="CAR"))
        for part in d.parts:
            if part.cl is None:
                continue
            if part.kind == "box":
                m = part.meta
                pts = ([(m["x0"], m["y"] - m["W"] / 2), (m["x1"], m["y"] - m["W"] / 2),
                        (m["x1"], m["y"] + m["W"] / 2), (m["x0"], m["y"] + m["W"] / 2)] if view == "TOP"
                       else [(m["x0"], m["z"] - m["H"] / 2), (m["x1"], m["z"] - m["H"] / 2),
                             (m["x1"], m["z"] + m["H"] / 2), (m["x0"], m["z"] + m["H"] / 2)])
                msp.add_lwpolyline(pts, close=True, dxfattribs=dict(layer="BOX"))
                continue
            layer = {"tube": "PIPE", "tip": "PIPE", "resonator": "RES"}.get(part.kind, "PIPE")
            arr = part.cl[:, [0, 2]] if view == "SIDE" else part.cl[:, [0, 1]]
            xy = [(float(a), float(b)) for a, b in arr]
            msp.add_lwpolyline(xy, dxfattribs=dict(layer=layer))
            for sgn in (-1, 1):
                msp.add_lwpolyline([(x, y + sgn * part.od / 2) for x, y in xy], dxfattribs=dict(layer=layer))
            if view == "TOP":
                for i, b in enumerate(part.bends, 1):
                    msp.add_circle((b["x"], b["y"]), 18, dxfattribs=dict(layer="BEND"))
                    msp.add_text(f"B{i} {b['angle']:.1f} R{b['R_mm']:.0f}",
                                 dxfattribs=dict(layer="BEND", height=38)).set_placement(
                        (b["x"] + 30, b["y"] + 40), align=ezdxf.enums.TextEntityAlignment.LEFT)
        for h in geo["hangers"]:
            msp.add_circle((h["x"], h["z"] if view == "SIDE" else h["y"]), 12, dxfattribs=dict(layer="HANGER"))
        dims = [(0, car.wheelbase, "колесная база"), (0, car.diff_x, "X до заднего бампера"),
                (geo["y_branch"]["x"], geo["box_in_x"], "Y → вход банок"),
                (geo["box_in_x"], geo["boxes"]["L"]["x1"], "банка"),
                (geo["boxes"]["L"]["x1"], geo["tips"]["x_end"], "банка → срез насадки")]
        for i, (x0, x1, lbl) in enumerate(dims):
            yy = -480 - i * 140 if view == "SIDE" else 1180 + i * 140
            msp.add_line((x0, yy), (x1, yy), dxfattribs=dict(layer="DIM"))
            for xx in (x0, x1):
                msp.add_line((xx, yy - 30), (xx, yy + 30), dxfattribs=dict(layer="DIM"))
            msp.add_text(f"{lbl} {x1 - x0:.0f}", dxfattribs=dict(layer="DIM", height=48)).set_placement(
                ((x0 + x1) / 2, yy + 55), align=ezdxf.enums.TextEntityAlignment.MIDDLE_CENTER)
        if view == "SIDE":
            for lbl, zz in (("низ банки", geo["z_box"] - p.box_h / 2), ("ось трубы", geo["z_line"]),
                            ("низ пола", car.floor_rear), ("низ бака", car.tank_bottom)):
                msp.add_line((FB - 260, zz), (600, zz), dxfattribs=dict(layer="DIM"))
                msp.add_text(f"{lbl} {zz:.0f}", dxfattribs=dict(layer="DIM", height=42)).set_placement(
                    (FB - 250, zz + 45), align=ezdxf.enums.TextEntityAlignment.LEFT)
        msp.add_text(f"ВИД {'СБОКУ' if view == 'SIDE' else 'СВЕРХУ'} — 1:1, мм (X от оси передних колёс)",
                     dxfattribs=dict(layer="TEXT", height=70)).set_placement(
            (FB, -1250), align=ezdxf.enums.TextEntityAlignment.LEFT)
    msp = doc.modelspace()
    y0 = -1700
    msp.add_text("РАСКРОЙ (развертка = длина по оси трубы; гибы отдельными позициями)",
                 dxfattribs=dict(layer="TEXT", height=60)).set_placement(
        (0, y0), align=ezdxf.enums.TextEntityAlignment.LEFT)
    for j, t in enumerate(["деталь", "OD", "стенка", "развертка", "прямые, мм", "гибы", "R гиба", "масса"]):
        msp.add_text(t, dxfattribs=dict(layer="TEXT", height=40)).set_placement(
            (j * 620, y0 - 150), align=ezdxf.enums.TextEntityAlignment.LEFT)
    for i, row in enumerate(d.cut_rows):
        yy = y0 - 270 - i * 100
        for j, v in enumerate([str(row["part"]), str(row["od_mm"]), str(row["wall_mm"]),
                               f'{row["developed_len_mm"]}', str(row["straight_lens_mm"])[:44],
                               f'{row["n_bends"]}x {row["bend_angles_deg"]}'[:36],
                               str(row["bend_radius_mm"]), str(row["mass_kg"])]):
            msp.add_text(v, dxfattribs=dict(layer="TEXT", height=34)).set_placement(
                (j * 620, yy), align=ezdxf.enums.TextEntityAlignment.LEFT)
    doc.saveas(path)


# ======================================================================================
# 6. ОТЧЁТЫ
# ======================================================================================


def write_files(d, car, engine, flow, aco, meshinfo, outdir):
    p = d.p
    for fname, rows in (("cutlist.csv", d.cut_rows), ("bends.csv", d.bend_rows),
                        ("bom.csv", d.geo["bom"])):
        with open(os.path.join(outdir, fname), "w", newline="", encoding="utf-8-sig") as fh:
            keys = list(rows[0].keys())
            w = csv.DictWriter(fh, fieldnames=keys, extrasaction="ignore")
            w.writeheader()
            for r in rows:
                w.writerow(r)
    mass_pipe = sum(float(r["mass_kg"]) for r in d.cut_rows if r["kind"] in ("tube", "resonator", "tip"))
    mass_box = sum(float(r["mass_kg"]) for r in d.cut_rows if r["kind"] == "box")
    spec = dict(vehicle="Opel Astra H Caravan (G09) 1.8 бензин", engine=engine,
                engine_data=ENGINE[engine], preset=p.name, preset_note=p.note,
                coordinate_system="X=0 ось передних колёс (+X назад), Y + влево, Z от земли, мм",
                pipe_schedule=dict(main_od=p.main_od, leg_od=p.leg_od, tail_od=p.tail_od, tip_od=p.tip_od,
                                   wall=p.wall, bend_radius_mm=round(d.r_factor * p.main_od, 1),
                                   std_bend_series=STD_BENDS),
                layout=d.geo, cut_list=d.cut_rows, bend_list=d.bend_rows, bom=d.geo["bom"],
                warnings=d.warnings,
                totals=dict(pipe_len_mm=d.geo["pipe_len_mm"], mass_pipe_kg=round(mass_pipe, 1),
                            mass_boxes_kg=round(mass_box, 1), mass_total_kg=round(mass_pipe + mass_box, 1),
                            **meshinfo),
                materials=("трубы и насадки: AISI 304L (бюджет: 409L или алюминированная 1.8 мм); "
                           "корпус банки 1.2, крышка 1.5; сердечник перфорированный, живое сечение ≥30 %; "
                           "набивка базальт/стекловолокно 100-128 кг/м3; хомуты нерж. 12 мм M8"),
                flow=flow, acoustics=aco)
    with open(os.path.join(outdir, "spec.json"), "w", encoding="utf-8") as fh:
        json.dump(spec, fh, ensure_ascii=False, indent=1, default=str)
    with open(os.path.join(outdir, "README.md"), "w", encoding="utf-8") as fh:
        fh.write(make_readme(d, car, engine, flow, aco, spec))
    return spec


def make_readme(d, car: Car, engine, flow, aco, spec):
    p, geo, c = d.p, d.geo, d.geo["clearance"]
    e = ENGINE[engine]
    dr = aco["drone_hits"]
    warn = "\n".join(f"* ⚠ {w}" for w in d.warnings) or "* замечаний нет"
    return f"""# Раздвоенная выхлопная трасса — Opel Astra H 1.8 Caravan (универсал, G09)

Сгенерировано `gen_exhaust.py`: пресет `{p.name}`, мотор `{engine}` ({e['hp']} л.с., {e['torque']} Н·м при {e['torque_rpm']}).
Пересчёт: `python gen_exhaust.py --preset {p.name} --engine {engine} --out out` (ключи — `--help`).

{p.note}

**Это проект для изготовления в мастерской, не «болт-он» из магазина.** Координаты и зазоры —
номинал компоновки: перед резкой делается примерка каркаса и обмер живых точек (штатное шаровое,
проушины, бак, тормозная трубка, арки, фаркоп, ГБО).

## Архитектура
| узел | как сделано |
|---|---|
| вход | штатное шаровое/фланец **после** катализатора и 2-го лямбда-зонда: кат, гофра, оба датчика, пламегасители — сохранены |
| главная | Ø{p.main_od:.1f}×{p.wall}, на высоте Z={geo['z_line']:.0f} мм, по тоннелю (y≈0) |
| резонатор | {'Ø%.0f×%.0f сквозной перед Y' % (p.res_d, p.res_l) if p.resonator else 'нет (пресет спорт)'} |
| развилка | true-Y Ø{p.main_od:.0f}→2×Ø{p.leg_od:.0f}, x={geo['y_branch']['x']:.0f}; {geo['y_branch']['note']} |
| ножки Y | по одному гибу {abs(geo['leg_angles_deg']['L']):.1f}° + прямая: L {geo['leg_len_mm']['L']:.0f} / R {geo['leg_len_mm']['R']:.0f} мм (Δ {geo['leg_len_mm']['delta']:.0f} мм, допуск ±10) |
| банки | овал {p.box_l:.0f}×{p.box_w:.0f}×{p.box_h:.0f}, объём {aco['box_volume_l']:.1f} л/шт, сердечник Ø{p.core_id:.0f}, 2 перегородки, набивка; вход в переднюю морду под углом ножки |
| хвосты | Ø{p.tail_od:.0f}: L {geo['tail_dev_mm']['L']:.0f} / R {geo['tail_dev_mm']['R']:.0f} мм, гиб {geo['tips']['angle_deg']:.0f}° к краю |
| насадки | 2×Ø{p.tip_od:.0f}, {'окна в бампере' if d.tip_in_bumper else 'из-под бампера, без подрезки'}, y={geo['tips']['y_L']:.0f}/{geo['tips']['y_R']:.0f}, вылет {c['rear_bumper_protrusion_mm']:.0f} мм |
| подвесы | {len(geo['hangers'])} шт (3 штатных резины + 4 новых кронштейна + хомут резонатора) |
| масса | {spec['totals']['mass_total_kg']:.1f} кг (труба {spec['totals']['mass_pipe_kg']:.1f} + банки {spec['totals']['mass_boxes_kg']:.1f}); сток-комплект ≈23-28 кг |
| раскрой | {geo['pipe_len_mm'] / 1000:.2f} м трубы, {len(d.bend_rows)} гибов (см. `bends.csv`) |

## Почему так скомпоновано под универсал
* База Caravan 2703 мм, задний свес ~{car.rear_overhang:.0f} мм → под полом **за осью** есть коридор
  {geo['box_in_x']:.0f}→{car.diff_x:.0f} мм, свободный от бака (x {car.tank_x0:.0f}-{car.tank_x1:.0f}) и от торсиона
  (зазор {c['beam_gap_mm']:.0f} мм, до бака в плане {c['tank_gap_mm']:.0f} мм).
* Банки низкие овальные (высота {p.box_h:.0f} мм), а не Ø127: иначе на гружёном универсале
  либо угол съезда {c['departure_angle_deg']:.1f}°, либо контакт с полом (зазор {c['floor_gap_rear_mm']:.0f} мм).
* Главная, Y и ножки лежат на одной высоте Z={geo['z_line']:.0f} мм и проходят **под баком**:
  до низа бака {c['tank_bottom_gap_mm']:.0f} мм (пересечение в плане: {c['tank_plan_cross']}). Штатно у
  Astra H под трубу в баке есть канавка — используй её, не пытайся «поднять» трассу выше пола.
* Все гибы — в одной плоскости каждый (yaw в плане либо pitch наклон) и из стандартного ряда:
  трасса собирается из прямых и **одного** гиба на ножку, без 3D-гибов и без правки болванкой.
* Ножки зеркальные и равные (Δ {geo['leg_len_mm']['delta']:.0f} мм) — иначе разность фаз на 2-й гармонике
  и «бульканье» на 2000-2500 об/мин.
* Хвост короткий ({aco['tail_len_mm']:.0f} мм) → 1/4 волны {aco['f_quarter_wave_hz']:.0f} Гц, то есть выше
  основной импульсной частоты на максимальном моменте — это и есть защита от гула на кузове-«резонаторе».

## Гидравлика
Модель: Дарси–Вейсбах (Colebrook) + местные потери + потеря выхода; поток из расхода воздуха по
РНХ и η_нап. Стационарная (без пульсаций — они дают ±20 %).

| об/мин | воздух, г/с | Q горячих газов, л/с | ΔP, кПа | ΔP, psi |
|---|---|---|---|---|
""" + "\n".join(
        f"| {r['rpm']} | {r['air_g_s']:.0f} | {r['Q_hot_l_s']:.0f} | {r['dp_kPa']:.2f} | {r['dp_psi']:.3f} |"
        for r in flow["rows"]) + f"""

* Скорости газа при максимальном потоке: {" · ".join(f"{k} {v:.0f} м/с (M{flow['mach_at_max_flow'][k]:.2f})" for k, v in flow['velocity_at_max_flow_m_s'].items())}.
  Число М выше 0.25 — это уже кратные потери на трение; здесь максимум {max(flow['mach_at_max_flow'].values()):.2f},
  т.е. 90 м/с в главной — не «задушено», а норма для 1.8 на отсечке (скорость звука в потоке {flow['sound_speed_m_s']:.0f} м/с).
* Пик противодавления {max(r['dp_kPa'] for r in flow['rows']):.1f} кПа = {max(r['dp_psi'] for r in flow['rows']):.2f} psi
  при {e['rpm_max']} об/мин — хорошо (проблема начинается с ~35 кПа).
* Пропускная способность при 0.25 psi против стоко-подобного Ø54 в один поток:
  **{flow['flow_at_0p25psi_m3s']['gain_pct']:+.0f} %** → на атмосферном 1.8 это ≈0…3 л.с.
  Раздвоенная трасса здесь = звук и вид, не «+10 л.с.». Расширять главную больше Ø63.5 не нужно:
  на {e['hp']}-сильном моторе упадёт скорость потока и тяга на низах/в середине.
* Кат сохранять обязательно: с катом Ø60.5 — оптимум. Вырезанный кат = другое сопротивление,
  тогда имеет смысл Ø63.5 + пламегаситель (и это уже «вмешательство» — см. легальность).

## Звук
* Реактивное затухание 1-й камеры банки: {aco['TL_box1_dB']} дБ; 2-я камера (0.55·L): {aco['TL_box2_dB']} дБ
  (степень расширения B = {aco['expansion_ratio']:.1f}).
* Поглощение набивкой: {aco['absorption_dB']} дБ. Итоговая вставка (оценка): {aco['insertion_loss_est_dB']} дБ.
* Импульсы 4-цил.: 2·n/60 = {aco['pulse_hz_by_rpm']} Гц.
* Хвост {aco['tail_len_mm']:.0f} мм: 1/4 волны {aco['f_quarter_wave_hz']:.0f} Гц, 1/2 {aco['f_half_wave_hz']:.0f} Гц.
* Совпадений с гармониками в 800…5000 об/мин: **{len(dr)}**{'; '.join(' → ' + f"{h['rpm']} об/мин, {h['harm']}·f={h['f_excite']:.0f} Гц против {h['mode']} {h['f_pipe']:.0f} Гц" for h in dr)}.
  Если на машине всё-таки гудит 1800-2800 об/мин: (1) резонатор Гельмгольца объёмом {aco['helmholtz_volume_l']} л
  с шеей Ø45×120 мм на частоту {list(aco['helmholtz_volume_l'].keys())} Гц, (2) свежая набивка 100-128 кг/м³,
  (3) изменить развертку хвоста на 40-60 мм (`--tip-angle`, `--box-bottom`, `--box-x`).

## Зазоры (`png/clearances.png`)
* низ системы → дорога **{c['ground_min_mm']:.0f} мм** (клиренс авто {car.ground_clearance:.0f} мм)
* банка → пол **{c['floor_gap_rear_mm']:.0f} мм**; трасса → низ бака по высоте **{c['tank_bottom_gap_mm']:.0f} мм**;
  банка → бак (план) **{c['tank_gap_mm']:.0f} мм**, перекрытие по X: {c['tank_x_overlap']}
* банка → зона торсиона **{c['beam_gap_mm']:.0f} мм** ({c['beam_note']})
* → тормозная магистраль {c['brake_line_gap_mm']:.0f} мм; → зона фаркопа {c['hitch_zone_gap_mm']:.0f} мм; → арка {c['wheelhouse_gap_mm']:.0f} мм
* вылет за бампер {c['rear_bumper_protrusion_mm']:.0f} мм, угол съезда {c['departure_angle_deg']:.1f}°

## Предупреждения генератора
{warn}

## Вариант «обе насадки слева» (--tips left)
Нужен, когда справа занят бампер/кузов (фаркоп, трейлер, ремонт) или когда так хочет владелец.
Плата: правая ножка идёт через весь кузов под баком, пересекает тормозную магистраль (см.
предупреждения — проходи под трубкой с зазором ≥40 мм и втулкой или вынеси перемычку вперёд,
за x=2350), обе банки смещают массу влево, а длины ножек разные → симметрия звука теряется.
Для 1.8 без наддуства в этом варианте держи обе банки одного объёма и не убирай резонатор.

## Изготовление
1. Труба — мандрел-гиб, R = {d.r_factor * p.main_od:.0f} мм ({d.r_factor:.1f}·D), углы из ряда {STD_BENDS}.
   `bends.csv` = карта гибов (деталь, угол, плоскость, длина дуги, координата). `cutlist.csv` = развертки
   и прямые куски: каждая деталь = «прямая + гиб + прямая», варится по месту ±25 мм.
2. Вход — на штатное шаровое (или фланец после 2-го лямбда-зонда): ±8° компенсации и съём без сварки.
3. Банки: стык «мама/папа» + хомут; корпус не переваривать, внутри — без сосулек (иначе дребезг и карманы).
4. Равность ножек Y и длину хвостов проверять **на машине** (±10 мм), не по чертежу.
5. Новые кронштейны H4/H5 — к усилителю порога/полу, шов 30 мм; **не** к тонкому полу и **не** к тормозной
   трубке; перед сваркой снять подкрылки, закрыть бак и трубки экраном.
6. Дренаж Ø4.5 мм — по отверстию в нижней задней кромке каждой банки и резонатора.
7. Зазор в хомутах ≥15 мм на весь ход подвески; после 150-200 км — контрольная протяжка; после первой
   большой загрузки — контроль зазоров (универсал садится на 40-60 мм).

## Легальность и что проверить
* Катализатор + оба лямбда-зонда сохранены → по CO/CH/NOx как сток; «раздвоенный выхлоп» сам по себе
  нейтрализатор не отменяет. Если кат уже вырезан предыдущим владельцем — это отдельный разговор.
* Шум: практический ориентир — не более +3 дБ(А) к паспортному значению ЗМ (табличка/сервисная книжка/
  протокол замера). `tour` ≈ +2…4 дБ(А), `sport` +6…10 — зона риска на техосмотре.
* Насадки: без острых кромок (развальцовка, R≥1), не шире габарита, на нормальных хомутах (не «холодная
  сварка», не саморезы в пластик).
* ГБО: баллон чаще всего в нише запаски — как раз за осью; проверь, что x≥{geo['box_in_x']:.0f} и y=±{geo['box_y']:.0f}
  не спорят с кронштейном баллона и мультиклапаном.
* Фаркоп: кронштейн/поперечина x≈{car.hitch_x:.0f}, зазор {c['hitch_zone_gap_mm']:.0f} мм; если фаркоп планируется —
  скажи сварщику заранее, банки сдвигают вперёд на 60-80 мм.
* Готовые катбэки ищи строго «Astra H Caravan / универсал 1.8» (Z18XE/Z18XER): версии для хэтчбека/GTC
  (например, 2×Ø80) отличаются длиной хвостов и окном бампера и на универсал встают только с переваркой
  хвостов — то есть работа та же, что сварка своей трассы.

## Файлы
| файл | зачем |
|---|---|
| `3d/astra_h_dual_exhaust.glb` | посмотреть/покрутить (Blender, FreeCAD, viewgltf.com) |
| `3d/*.stl` | каждая деталь; `..._assembly.stl` — сборка одним файлом (без булева) |
| `dxf/exhaust_astra_h_{p.name}.dxf` | чертёж 1:1, 2 вида + размеры + таблица раскроя (Компас/AutoCAD/libreCAD) |
| `scad/exhaust_astra_h_{p.name}.scad` | правка OD/углов текстом → F6 → STL (STEP — импорт в FreeCAD) |
| `png/*.png` | 3D-рендер (2 ракурса), 2 вида, схема гибов, диаграмма зазоров |
| `cutlist.csv` `bends.csv` `bom.csv` | раскрой, карта гибов, спецификация |
| `spec.json` | всё числами (для своего скрипта/Excel) |

## Дисклеймер
Основа — открытые габариты кузова (L 4515, база 2703, ширина 1753, высота 1500, клиренс 165) и типовая
компоновка Z18XE/Z18XER (кат+гофра впереди, штатный тракт ≈Ø54, длинный резонатор, одна банка под
полом). Привязка к усилителям, проушинам, баку, тормозным трубкам и подкрылкам конкретной машины не
измерялась. Зазоры до горючих и тормозных узлов, прочность кронштейнов, отсутствие контакта с проводкой
и проверка после установки — ответственность изготовителя и установщика. Сварка выхлопа рядом с баком =
пожароопасная работа: снимай минус, проветривай, экран, огнетушитель под рукой.
"""


# ======================================================================================


def main():
    ap = argparse.ArgumentParser(description="раздвоенная выхлопная трасса Astra H 1.8 Caravan")
    ap.add_argument("--preset", default="tour", choices=list(PRESETS))
    ap.add_argument("--engine", default="Z18XER", choices=list(ENGINE))
    ap.add_argument("--tips", default="both", choices=["both", "left"],
                    help="both — 2 насадки вразножку; left — обе слева")
    ap.add_argument("--mandrel", type=float, default=1.6, help="радиус гиба в ×OD")
    ap.add_argument("--tip-in-bumper", action="store_true", help="насадки в окнах бампера (нужна подрезка)")
    ap.add_argument("--tip-angle", type=float, default=22.5, help="гиб хвоста к краю, град (0 = прямо)")
    ap.add_argument("--box-bottom", type=float, default=255.0, help="зазор низа банки до дороги, мм")
    ap.add_argument("--box-x", type=float, default=2860.0, help="X передней морды банки, мм от оси перёд. колёс")
    ap.add_argument("--box-y", type=float, default=0.0,
                    help="смещение центра банки к краю, мм (0 = авто: 60 для «вразножку», 0 для «обе слева»)")
    ap.add_argument("--z-line", type=float, default=330.0,
                    help="высота оси главной магистрали (и банок/хвостов), мм от дороги")
    ap.add_argument("--oem-outlet", type=float, nargs=3, default=None, metavar=("X", "Y", "Z"),
                    help="координаты среза штатной трубы после ката (померить на машине)")
    ap.add_argument("--out", default="out")
    a = ap.parse_args()
    car, p = Car(), PRESETS[a.preset]
    if a.oem_outlet:
        car.oem_outlet = tuple(a.oem_outlet)
    d = ExhaustDesign(car, p, a.engine, tips_side=a.tips, r_factor=a.mandrel,
                      tip_in_bumper=a.tip_in_bumper, box_bottom=a.box_bottom, tip_angle=a.tip_angle,
                      box_in_x=a.box_x, box_off=a.box_y, z_line=a.z_line)
    flow = flow_model(d, a.engine)
    aco = acoustics(p, d.geo)
    os.makedirs(a.out, exist_ok=True)
    for sub in ("3d", "dxf", "png", "scad"):
        os.makedirs(os.path.join(a.out, sub), exist_ok=True)
    mi = export_meshes(d, a.out)
    export_scad(d, car, os.path.join(a.out, "scad", f"exhaust_astra_h_{p.name}.scad"))
    render_png(d, car, os.path.join(a.out, "png"))
    export_dxf(d, car, os.path.join(a.out, "dxf", f"exhaust_astra_h_{p.name}.dxf"))
    spec = write_files(d, car, a.engine, flow, aco, mi, a.out)
    print(f"OK preset={p.name} engine={a.engine} parts={mi['n_parts']} "
          f"ΔPmax={max(r['dp_kPa'] for r in flow['rows']):.2f} кПа drone={len(aco['drone_hits'])} "
          f"mass={spec['totals']['mass_total_kg']:.1f} кг pipe={d.geo['pipe_len_mm'] / 1000:.2f} м "
          f"bends={len(d.bend_rows)}")
    print("    зазоры: " + ", ".join(f"{k}={v}" for k, v in d.geo["clearance"].items()))
    for w in d.warnings:
        print("    WARN:", w)


if __name__ == "__main__":
    main()
