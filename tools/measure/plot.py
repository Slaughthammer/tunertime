"""plot.py - draws the measurement plots from the CSVs in out/ (see run.sh).

python tools/measure/plot.py tools/measure/out
"""
import csv
import math
import os
import sys
from collections import defaultdict

import matplotlib
matplotlib.use("Agg")
import matplotlib.pyplot as plt
import matplotlib.ticker
import numpy as np
from matplotlib.colors import ListedColormap

OUT = sys.argv[1] if len(sys.argv) > 1 else os.path.join(
    os.path.dirname(__file__), "out")

# categorical slots, fixed order (dataviz reference palette, light mode)
C = ["#2a78d6", "#eb6834", "#1baf7a", "#eda100", "#e87ba4", "#008300",
     "#6250d6", "#e34948"]
INK, INK2, GRID = "#0b0b0b", "#52514e", "#e4e3df"
NONE_GREY = "#c9c8c2"

plt.rcParams.update({
    "figure.facecolor": "#fcfcfb", "axes.facecolor": "#fcfcfb",
    "axes.edgecolor": INK2, "axes.labelcolor": INK, "text.color": INK,
    "xtick.color": INK2, "ytick.color": INK2, "axes.grid": True,
    "grid.color": GRID, "grid.linewidth": 0.8, "axes.spines.top": False,
    "axes.spines.right": False, "font.size": 9, "lines.linewidth": 1.8,
    "legend.frameon": False, "savefig.dpi": 100,
})

SIG = ["open flue", "stopped (Gedackt)", "reed (Trompete)",
       "missing fundamental", "noisy (SNR 10 dB)", "phone mic (HP 150 Hz)",
       "stopped + phone mic", "noisy (SNR 0 dB)",
       "open, wind wander 0.5 c"]
NS = len(SIG)
NAMES = ["A", "A#", "B", "C", "C#", "D", "D#", "E", "F", "F#", "G", "G#"]


def note_name(semi):
    k = semi + 48
    return "%s%d" % (NAMES[k % 12], (k + 9) // 12)


def read(name):
    path = os.path.join(OUT, name)
    if not os.path.exists(path):
        print("missing", path)
        return None
    with open(path, newline="") as f:
        rows = list(csv.DictReader(f))
    for r in rows:
        for k, v in r.items():
            try:
                r[k] = float(v)
            except (TypeError, ValueError):
                if isinstance(v, str) and "#" in v:
                    r[k] = float("nan")	# MSVCRT printf of NaN
    return rows


def c_ticks(ax, lo, hi, axis="x"):
    """ticks at every C between semis lo..hi (from A4)"""
    ticks = [s for s in range(lo, hi + 1) if (s + 48) % 12 == 3]
    if axis == "x":
        ax.set_xticks(ticks)
        ax.set_xticklabels([note_name(s) for s in ticks])
    else:
        ax.set_yticks(ticks)
        ax.set_yticklabels([note_name(s) for s in ticks])


def save(fig, name):
    path = os.path.join(OUT, name)
    fig.savefig(path, bbox_inches="tight")
    plt.close(fig)
    try:	# palette PNG: a third of the size, so the plots can be committed
        from PIL import Image
        im = Image.open(path).convert("RGB")
        im.quantize(colors=96, method=Image.Quantize.MEDIANCUT).save(
            path, optimize=True)
    except ImportError:
        pass
    print("wrote", name)


# ---------------------------------------------------------------- Q1
def q1():
    rows = read("detect_map.csv")
    if not rows:
        return
    semis = sorted({int(r["semi"]) for r in rows})
    lo, hi = semis[0], semis[-1]
    grid = np.full((NS, len(semis)), 3.0)
    frac = np.zeros((NS, len(semis)))
    lat = np.full((NS, len(semis)), np.nan)
    for r in rows:
        i, j = int(r["type"]), int(r["semi"]) - lo
        cnt = [r["correct"], r["octave"], r["other"], r["none"]]
        n = sum(cnt)
        if n > 0:
            k = int(np.argmax(cnt))
            grid[i, j] = k
            frac[i, j] = cnt[0] / n
        lat[i, j] = r["lat_follow"]

    fig, (a1, a2) = plt.subplots(2, 1, figsize=(11, 7),
                                 gridspec_kw={"height_ratios": [1.2, 1]})
    cmap = ListedColormap([C[0], C[1], C[7], NONE_GREY])
    a1.imshow(grid, aspect="auto", cmap=cmap, vmin=-0.5, vmax=3.5,
              interpolation="nearest",
              extent=(lo - 0.5, hi + 0.5, NS - 0.5, -0.5))
    # cells correct by majority but not always: dot
    for i in range(NS):
        for j, s in enumerate(semis):
            if grid[i, j] == 0 and frac[i, j] < 0.95:
                a1.plot(s, i, "o", ms=2.5, color="white")
    a1.set_yticks(range(NS))
    a1.set_yticklabels(SIG)
    a1.grid(False)
    c_ticks(a1, lo, hi)
    a1.set_title("Q1  Detector in steady state (estimates 1-3.5 s after "
                 "onset, majority)", loc="left", fontsize=10)
    from matplotlib.patches import Patch
    a1.legend(handles=[Patch(color=C[0], label="correct"),
                       Patch(color=C[1], label="octave error"),
                       Patch(color=C[7], label="other wrong note"),
                       Patch(color=NONE_GREY, label="nothing"),
                       plt.Line2D([], [], marker="o", ls="", color=INK2,
                                  ms=3, label="correct < 95 % of the time")],
              ncol=5, loc="upper left", bbox_to_anchor=(0, -0.1),
              fontsize=8)
    for i in range(min(NS, len(C))):	# 8 slots; the 9th is in the map
        y = lat[i]
        a2.plot(semis, y, color=C[i], label=SIG[i], lw=1.4)
    a2.set_ylabel("follow latency, s\n(onset to 3 agreeing correct "
                  "estimates)")
    a2.set_ylim(0, 1.0)
    c_ticks(a2, lo, hi)
    a2.set_xlim(lo - 0.5, hi + 0.5)
    a2.legend(ncol=4, fontsize=8, loc="upper center")
    a2.set_xlabel("sounding pitch (32' C = C0, 8' C = C2, top of "
                  "manual c'''' = C7)")
    fig.tight_layout()
    save(fig, "q1_detector_map.png")

    # summary for FINDINGS
    lines = []
    for i in range(NS):
        k = grid[i]
        bad = [note_name(s) for j, s in enumerate(semis) if k[j] != 0]
        lines.append("%s: %d/%d correct; wrong at %s; median follow "
                     "latency %.2f s" % (SIG[i], int(np.sum(k == 0)),
                                         len(semis), compress(bad),
                                         np.nanmedian(lat[i])))
    hb = read("detect_map_hb.csv")
    if hb:
        for i in range(NS):
            bad = []
            for r in hb:
                if int(r["type"]) != i:
                    continue
                cnt = [r["correct"], r["octave"], r["other"], r["none"]]
                if sum(cnt) == 0 or int(np.argmax(cnt)) != 0:
                    bad.append(note_name(int(r["semi"])))
            lines.append("half-band variant, %s: wrong at %s" %
                         (SIG[i], compress(bad)))
    write_summary("q1", lines)


def compress(names):
    if not names:
        return "-"
    if len(names) > 14:
        return ", ".join(names[:6]) + " ... " + ", ".join(names[-4:]) + \
            " (%d notes)" % len(names)
    return ", ".join(names)


SUMMARY = {}


def write_summary(key, lines):
    SUMMARY[key] = lines


# ---------------------------------------------------------------- Q2
def q2():
    rows = read("detect_acc.csv")
    if not rows:
        return
    types = [0, 1, 2, 4, 5, 8]
    by = defaultdict(list)
    for r in rows:
        by[(int(r["type"]), int(r["semi"]))].append(r)
    semis = sorted({int(r["semi"]) for r in rows})
    lo, hi = semis[0], semis[-1]

    fig, axs = plt.subplots(3, 1, figsize=(11, 9.5), sharex=True)
    a1, a2, a3 = axs
    lines = []
    for n, t in enumerate(types):
        bias = []
        sd = []
        p95 = []
        stab = []
        swrong = []
        for s in semis:
            rs = by[(t, s)]
            b = [abs(r["err_mean"]) for r in rs if not math.isnan(r["err_mean"])]
            bias.append(max(b) if b else np.nan)
            d = [r["err_sd"] for r in rs if not math.isnan(r["err_sd"])]
            sd.append(np.mean(d) if d else np.nan)
            p = [r["win_abs_err_p95"] for r in rs]
            p95.append(np.nanmax(p) if p else np.nan)
            stab.append(np.nanmean([r["win_mad_mean"] for r in rs]))
            m = [r["stable_abs_err_max"] for r in rs]
            swrong.append(np.nanmax(m) if not all(map(math.isnan, m))
                          else np.nan)
        col = C[[0, 1, 2, 4, 5, 6][n]]
        a1.plot(semis, bias, color=col, label=SIG[t])
        a1.plot(semis, sd, color=col, ls=":", lw=1.2)
        a2.plot(semis, p95, color=col, label=SIG[t])
        a3.plot(semis, stab, color=col, label=SIG[t])
        sd = np.array(sd)
        p95 = np.array(p95)
        swrong = np.array(swrong)
        lines.append(
            "%s: single-estimate sd median %.2f c (C2-C7: %.2f c), worst "
            "|bias| %.1f c; 1-s median error p95 <= 1 c at %d/%d notes; "
            "'stable' (MAD<1c) but > 1 c wrong at %d notes (worst %.1f c)"
            % (SIG[t], np.nanmedian(sd),
               np.nanmedian(sd[(np.array(semis) >= -33) & (np.array(semis) <= 27)]),
               np.nanmax(bias), int(np.sum(p95 <= 1.0)), len(semis),
               int(np.sum(swrong > 1.0)), np.nanmax(swrong)))
    for a in (a1, a2):
        a.set_yscale("log")
        a.axhline(1.0, color=INK, lw=1, ls="--")
    a1.set_ylim(0.001, 30)
    a2.set_ylim(0.001, 30)
    a3.set_ylim(0.001, 30)
    a3.set_yscale("log")
    a3.axhline(1.0, color=INK, lw=1, ls="--")
    hb = read("detect_acc_hb.csv")
    if hb:
        for t, col in ((0, C[0]), (1, C[1])):
            p95 = []
            for s in semis:
                v = [r["win_abs_err_p95"] for r in hb
                     if int(r["type"]) == t and int(r["semi"]) == s]
                p95.append(np.nanmax(v) if v else np.nan)
            a2.plot(semis, p95, color=col, ls="--", lw=1.0,
                    label=SIG[t] + ", half-band variant")
            p95 = np.array(p95)
            lines.append("half-band variant, %s: 1-s median error p95 "
                         "<= 1 c at %d/%d notes, worst %.2f c, notes > 0.3 "
                         "c: %d" % (SIG[t], int(np.sum(p95 <= 1.0)),
                                    len(semis), np.nanmax(p95),
                                    int(np.sum(p95 > 0.3))))
        a2.legend(fontsize=8, loc="upper right")
    a1.text(lo, 1.05, "STABLE_CENTS = 1", fontsize=8, va="bottom")
    a1.set_ylabel("cents\nsolid: worst |bias| over 5 offsets\n"
                  "dotted: sd of single estimates")
    a1.set_title("Q2  Detector frequency error (estimates within 50 c of "
                 "the pipe; 1-5 s after onset)", loc="left", fontsize=10)
    a1.legend(ncol=3, fontsize=8, loc="upper right")
    a2.set_ylabel("cents, 95th pct of |error|\nof length mode's 1-s "
                  "median")
    a3.set_ylabel("spread length mode sees:\nmean MAD of the 1-s window, c")
    c_ticks(a3, lo, hi)
    a3.set_xlim(lo - 0.5, hi + 0.5)
    a3.set_xlabel("sounding pitch")
    fig.tight_layout()
    save(fig, "q2_detector_cents.png")
    lines.append("estimates per second: %.2f (fs/2/HOP = 22050/2048)" %
                 (22050 / 2048))
    write_summary("q2", lines)
    return {t: None for t in types}


# ---------------------------------------------------------------- Q3
def rms(v):
    v = [x for x in v if not math.isnan(x)]
    return math.sqrt(sum(x * x for x in v) / len(v)) if v else float("nan")


def q3():
    rows = read("drift_time.csv")
    if not rows:
        return
    feet = ["32'", "16'", "8'", "4'", "2 2/3'", "1'"]
    snrs = [40, 20, 10, 0]
    Ts = sorted({r["T"] for r in rows})
    keys = sorted({int(r["semi"]) for r in rows})
    g = defaultdict(list)
    for r in rows:
        g[(r["footage"], r["snr"], r["T"])].append(r)
    fig, axs = plt.subplots(2, 3, figsize=(11, 6.6), sharex=True, sharey=True)
    for fi, ft in enumerate(feet):
        a = axs.flat[fi]
        for si, snr in enumerate(snrs):
            yw = [rms([r["err_warm"] for r in g[(ft, snr, T)]]) for T in Ts]
            yc = [rms([r["err_cold"] for r in g[(ft, snr, T)]]) for T in Ts]
            a.plot(Ts, yw, color=C[si], marker="o", ms=3,
                   label="SNR %d dB" % snr)
            a.plot(Ts, yc, color=C[si], ls=":", lw=1.2)
        a.set_xscale("log")
        a.set_yscale("log")
        a.axvline(2.0, color=INK2, lw=1, ls="--")
        a.axhline(0.1, color=INK, lw=0.8, ls="-.")
        a.set_title(ft + " row", loc="left", fontsize=9)
        a.set_xticks([0.25, 0.5, 1, 2, 4])
        a.set_xticklabels(["0.25", "0.5", "1", "2", "4"])
        a.xaxis.set_minor_formatter(matplotlib.ticker.NullFormatter())
    axs[0, 0].legend(fontsize=7, loc="lower left")
    for a in axs[:, 0]:
        a.set_ylabel("RMS error, cents")
    fig.supxlabel("usable seconds measured (MEASURE_SECONDS = 2: dashed; "
                  "0.1 c: dash-dot)", fontsize=9)
    fig.suptitle("Q3  Mixture measurement (focused-row drift) error vs. "
                 "time, keys C2-C7, open flue, offset +-20 c\n"
                 "solid: pipe already sounding (warm); dotted: key played "
                 "as the row is set (cold)", x=0.01, ha="left", fontsize=10)
    fig.tight_layout()
    save(fig, "q3_drift_time.png")

    # wall-clock time to finish 2 s, and cold bias, per footage x key
    fig, (a1, a2) = plt.subplots(1, 2, figsize=(11, 3.8))
    lines = []
    onset = read("drift_time_onset.csv") or []

    def per_key(src, ft):
        xs, wall, bias = [], [], []
        for k in keys:
            rs = [r for r in src if r["footage"] == ft and
                  int(r["semi"]) == k and r["T"] == 2.0 and r["snr"] == 20]
            if not rs:
                continue
            xs.append(k)
            wall.append(np.nanmedian([r["wall_cold"] for r in rs]))
            # pull towards 'in tune': slope of err vs off
            off = np.array([r["off"] for r in rs])
            e = np.array([r["err_cold"] for r in rs])
            bias.append(-100.0 * np.sum(e * off) / np.sum(off * off))
        return xs, wall, bias

    for fi, ft in enumerate(feet):
        xs, wall, bias = per_key(rows, ft)
        a1.plot(xs, wall, color=C[fi], marker="o", ms=3, label=ft)
        a2.plot(xs, bias, color=C[fi], marker="o", ms=3, label=ft)
        lines.append("%s: wall time to 2 usable s (cold) %s s; cold pull "
                     "to in-tune %s %%" % (ft, "/".join("%.1f" % w for w in wall),
                                           "/".join("%.1f" % b for b in bias)))
        if onset:
            xs, wall, bias = per_key(onset, ft)
            a1.plot(xs, wall, color=C[fi], ls=":", lw=1.2)
            a2.plot(xs, bias, color=C[fi], ls=":", lw=1.2)
            lines.append("   variant settle-at-onset: wall %s s; pull %s %%" %
                         ("/".join("%.1f" % w for w in wall),
                          "/".join("%.2f" % b for b in bias)))
            for T in (0.5, 1.0, 2.0):
                lines.append("   RMS cold T=%g SNR 20: as is %.3f c, variant "
                             "%.3f c" % (T, rms([r["err_cold"] for r in rows
                                                 if r["footage"] == ft and r["T"] == T and r["snr"] == 20]),
                                         rms([r["err_cold"] for r in onset
                                              if r["footage"] == ft and r["T"] == T and r["snr"] == 20])))
    a1.axhline(2.0, color=INK2, ls="--", lw=1)
    a1.set_ylabel("wall seconds until a 2-s\nmeasurement is done (cold)")
    a2.set_ylabel("cold measurement pulled towards\n'in tune', % of the "
                  "offset (T = 2 s)")
    for a in (a1, a2):
        c_ticks(a, keys[0], keys[-1])
        a.set_xlabel("key (8' pitch)")
    a1.legend(fontsize=8, ncol=2)
    fig.suptitle("Q3  Key played as the measurement starts (cold), SNR 20 dB."
                 "  solid: strobe.c as it is; dotted: variant that settles "
                 "again at each onset", x=0.01, ha="left", fontsize=10)
    fig.tight_layout()
    save(fig, "q3_drift_settle.png")

    for snr in snrs:
        for T in (1.0, 2.0):
            vals = []
            for ft in feet:
                vals.append("%s %.3f" % (ft, rms([r["err_warm"] for r in
                                                  g[(ft, snr, T)]])))
            lines.append("RMS warm T=%g s SNR %d dB: %s" % (T, snr,
                                                            ", ".join(vals)))
    write_summary("q3", lines)


def q3_beat():
    rows = read("drift_beat.csv")
    if not rows:
        return
    rows = [r for r in rows if not math.isnan(r["err"])]
    fig, (a1, a2) = plt.subplots(1, 2, figsize=(11, 4))
    lines = []
    keys = sorted({int(r["semi"]) for r in rows})
    rs_ = sorted({r["r"] for r in rows})
    n = 0
    for semi in keys:
        for d in (1, 2, 3):
            m, lo, hi = [], [], []
            for rr in rs_:
                v = [r["err"] / d for r in rows if int(r["semi"]) == semi
                     and r["delta"] == d and r["r"] == rr and r["T"] == 2.0]
                if not v:
                    v = [np.nan]
                m.append(np.mean(v))
                lo.append(min(v))
                hi.append(max(v))
            col = C[[0, 1, 6][d - 1]]
            ls = "-" if semi == keys[0] else ":"
            a1.plot(rs_, m, color=col, ls=ls, marker="o", ms=3,
                    label="%s, %d c" % (note_name(semi), d))
            a1.fill_between(rs_, lo, hi, color=col, alpha=0.10, lw=0)
            lines.append("%s 1' row, 2nd pipe +%d c: T=2 s error/delta by r "
                         "%s" % (note_name(semi), d, ", ".join(
                             "%.2f:%.2f..%.2f" % (rr, l, h)
                             for rr, l, h in zip(rs_, lo, hi))))
    a1.set_xscale("log")
    a1.set_xticks([0.2, 0.5, 1, 2, 5])
    a1.set_xticklabels(["0.2", "0.5", "1", "2", "5"])
    a1.xaxis.set_minor_formatter(matplotlib.ticker.NullFormatter())
    a1.axhline(0.5, color=INK2, lw=0.8, ls="--")
    a1.set_xlabel("amplitude ratio r (second pipe / tuned pipe)")
    a1.set_ylabel("measured offset / delta, T = 2 s\n"
                  "0 = the tuned pipe, 1 = the second pipe")
    a1.legend(fontsize=7, ncol=2)
    a1.set_title("which pipe is measured (band: min-max over 8 phases)",
                 loc="left", fontsize=9)
    semi = keys[0]
    for i, rr in enumerate((0.5, 0.85, 1.2)):
        rs = [r for r in rows if int(r["semi"]) == semi and r["delta"] == 2
              and r["r"] == rr]
        Ts = sorted({r["T"] for r in rs})
        for s in sorted({r["seed"] for r in rs}):
            y = [r["err"] for r in rs if r["seed"] == s]
            a2.plot(Ts[:len(y)], y, color=C[i], lw=0.9, alpha=0.7,
                    label="r = %.2f" % rr if s == 0 else None)
    a2.axvline(2.0, color=INK2, ls="--", lw=1)
    a2.axhline(0.0, color=INK, lw=0.8)
    a2.axhline(2.0, color=INK, lw=0.8)
    a2.set_xlabel("usable seconds measured")
    a2.set_ylabel("error vs. the tuned pipe, cents")
    a2.legend(fontsize=8)
    a2.set_title("%s, delta 2 c (beat period %.1f s), 8 start phases" %
                 (note_name(semi), 1 / (rs[0]["row_hz"] *
                                         (2 ** (2 / 1200) - 1))),
                 loc="left", fontsize=9)
    fig.suptitle("Q3  A second pipe on the focused partial (e.g. the 2' "
                 "rank's 2nd partial under the 1' rank), 1' row",
                 x=0.01, ha="left", fontsize=10)
    fig.tight_layout()
    save(fig, "q3_drift_beat.png")
    write_summary("q3beat", lines)


def q3_trem():
    rows = read("drift_trem.csv")
    if not rows:
        return
    fig, a = plt.subplots(1, 1, figsize=(11, 3.8))
    lines = []
    i = 0
    for rate in (4.5, 5.5, 6.5):
        for mult, ls in ((4, "-"), (32, ":")):
            rs = [r for r in rows if r["rate"] == rate and r["mult"] == mult]
            Ts = sorted({r["T"] for r in rs})
            y = [rms([r["err"] for r in rs if r["T"] == T]) for T in Ts]
            a.plot(Ts, y, color=C[[0, 1, 2][i // 2]], ls=ls, lw=1.4,
                   label="%.1f Hz, %s row" % (rate, "8'" if mult == 4
                                               else "1'"))
            y2 = rms([r["err"] for r in rs if abs(r["T"] - 2.0) < 1e-6])
            lines.append("tremulant %.1f Hz +-10 c, %s row: RMS error at "
                         "T=2 s %.2f c, worst over T in 1.5-2.5 s %.2f c" %
                         (rate, "8'" if mult == 4 else "1'", y2,
                          max(yy for T, yy in zip(Ts, y) if 1.5 <= T <= 2.5)))
            i += 1
    a.axvline(2.0, color=INK2, ls="--", lw=1)
    a.set_yscale("log")
    a.set_xlabel("usable seconds measured")
    a.set_ylabel("RMS error, cents")
    a.legend(fontsize=8, ncol=3)
    a.set_title("Q3  Tremulant on (FM +-10 c, AM 25 %), key C4: the mean "
                "is right only over whole tremulant periods",
                loc="left", fontsize=10)
    fig.tight_layout()
    save(fig, "q3_drift_tremulant.png")
    write_summary("q3trem", lines)


def q3_wind():
    rows = read("drift_wind.csv")
    if not rows:
        return
    fig, a = plt.subplots(1, 1, figsize=(11, 3.8))
    lines = []
    feet = {1: "32'", 4: "8'", 32: "1'"}
    for ji, jit in enumerate(sorted({r["jitter"] for r in rows})):
        for mult, ls in ((1, ":"), (4, "-"), (32, "--")):
            rs = [r for r in rows if r["jitter"] == jit and r["mult"] == mult]
            Ts = sorted({r["T"] for r in rs})
            y = [rms([r["err"] for r in rs if r["T"] == T]) for T in Ts]
            a.plot(Ts, y, color=C[ji], ls=ls, marker="o", ms=3,
                   label="%.2f c rms, %s row" % (jit, feet[mult]))
            lines.append("wander %.2f c rms (0.1 s), %s row: RMS error "
                         % (jit, feet[mult]) + ", ".join(
                             "T=%g %.3f" % (T, yy) for T, yy in zip(Ts, y)))
    T = np.array([0.25, 4.0])
    for ji, jit in enumerate(sorted({r["jitter"] for r in rows})):
        a.plot(T, jit * np.sqrt(2 * 0.1 / T), color=C[ji], lw=0.8, alpha=0.5)
    a.axvline(2.0, color=INK2, ls="--", lw=1)
    a.axhline(0.1, color=INK, ls="-.", lw=0.8)
    a.set_xscale("log")
    a.set_yscale("log")
    a.set_xticks([0.25, 0.5, 1, 2, 4])
    a.set_xticklabels(["0.25", "0.5", "1", "2", "4"])
    a.xaxis.set_minor_formatter(matplotlib.ticker.NullFormatter())
    a.set_xlabel("usable seconds measured")
    a.set_ylabel("RMS error vs. the long-term\nmean pitch, cents")
    a.legend(fontsize=7, ncol=3)
    a.set_title("Q3  Pipe pitch wandering with the wind (AR(1), 0.1 s; "
                "keys C2/C4/C6, SNR 30 dB); thin lines: sigma*sqrt(2 tau/T)",
                loc="left", fontsize=10)
    fig.tight_layout()
    save(fig, "q3_drift_wind.png")
    write_summary("q3wind", lines)


def q3_level():
    rows = read("drift_level.csv")
    if not rows:
        return
    fig, (a1, a2) = plt.subplots(1, 2, figsize=(11, 3.6))
    lines = []
    for i, semi in enumerate(sorted({int(r["semi"]) for r in rows})):
        rs = [r for r in rows if int(r["semi"]) == semi]
        lv = sorted({r["level"] for r in rs})
        e = [rms([r["err"] for r in rs if r["level"] == l]) for l in lv]
        w = [np.nanmedian([r["wall"] for r in rs if r["level"] == l]) for l in lv]
        a1.plot(lv, e, color=C[i], marker="o", ms=3, label=note_name(semi) + " 8'")
        a2.plot(lv, w, color=C[i], marker="o", ms=3, label=note_name(semi) + " 8'")
        lines.append("%s: " % note_name(semi) + ", ".join(
            "%g:%.2fc/%.1fs" % (l, ee, ww) for l, ee, ww in zip(lv, e, w)))
    for a in (a1, a2):
        a.set_xscale("log")
        a.set_xlabel("pipe level, rms in 16-bit units (mic noise 2 units)")
    a1.set_yscale("log")
    a1.set_ylabel("RMS error at T = 2 s, cents")
    a2.set_ylabel("wall seconds to 2 usable s")
    a1.legend(fontsize=8)
    fig.suptitle("Q3  Quiet pipes: DRIFT_MIN_AMP = 8 gates the blocks",
                 x=0.01, ha="left", fontsize=10)
    fig.tight_layout()
    save(fig, "q3_drift_level.png")
    write_summary("q3level", lines)


# ---------------------------------------------------------------- Q4
def speed(T):
    return 331.3 * math.sqrt(1 + T / 273.15)


def q4():
    semis = np.arange(-57, 40)
    f = 440.0 * 2.0 ** (semis / 12.0)
    c = speed(20.0)
    k = math.log(2) / 1200.0
    fig, axs = plt.subplots(1, 3, figsize=(12, 4))
    a1, a2, a3 = axs
    lines = []
    for i, (div, nm) in enumerate(((2.0, "open"), (4.0, "stopped"))):
        L = 1000.0 * c / (div * f)          # mm, acoustic length
        mmpc = L * k                        # mm per cent
        a1.plot(semis, mmpc, color=C[i], label=nm)
        # +-5 C wrong setting: dL scales with c
        err5 = abs(speed(25.0) / speed(20.0) - 1.0)
        a2.plot(semis, 10 * mmpc * err5, color=C[i],
                label="%s, pipe 10 c off" % nm)
        a3.plot(semis, 0.05 / mmpc, color=C[i], label=nm)
        for s in (-57, -33, -9, 15, 27, 39):
            j = list(semis).index(s)
            lines.append("%s %s: L %.0f mm, %.3f mm/cent, 'in tune' "
                         "(|dL| < 0.05 mm) = +-%.2f c" %
                         (nm, note_name(s), L[j], mmpc[j], 0.05 / mmpc[j]))
    a1.set_yscale("log")
    a1.axhline(0.1, color=INK, ls="--", lw=1)
    a1.text(-57, 0.11, "display step 0.1 mm", fontsize=8)
    a1.set_ylabel("mm of pipe length per cent")
    a1.set_title("mm per cent of pitch", loc="left", fontsize=9)
    a2.set_yscale("log")
    a2.axhline(0.1, color=INK, ls="--", lw=1)
    a2.set_ylabel("mm error from a 5 °C wrong\ntemperature (0.86 % of dL)")
    a2.set_title("temperature setting off by 5 °C", loc="left", fontsize=9)
    a3.set_yscale("log")
    a3.axhline(1.0, color=INK, ls="--", lw=1)
    a3.text(-57, 1.1, "STABLE_CENTS", fontsize=8)
    a3.set_ylabel("cents shown as 'in tune' (0.0 mm)")
    a3.set_title("'in tune' window", loc="left", fontsize=9)
    for a in axs:
        c_ticks(a, -57, 39)
        a.set_xlabel("sounding pitch")
        a.tick_params(axis="x", labelsize=7)
    a1.legend(fontsize=8)
    a2.legend(fontsize=8)
    fig.suptitle("Q4  Length mode: dL = c/(n f_act) - c/(n f_target), "
                 "c at 20 °C", x=0.01, ha="left", fontsize=10)
    fig.tight_layout()
    save(fig, "q4_length_mm.png")

    # meaningful resolution: precision in cents x mm/cent
    acc = read("detect_acc.csv")
    dt = read("drift_time.csv")
    if acc and dt:
        fig, a = plt.subplots(1, 1, figsize=(11, 4))
        for i, (div, nm) in enumerate(((2.0, "open"), (4.0, "stopped"))):
            t = 0 if div == 2.0 else 1
            xs, ys = [], []
            for s in semis:
                v = [r["win_abs_err_p95"] for r in acc
                     if int(r["semi"]) == s and int(r["type"]) == t]
                if v:
                    fs_ = 440.0 * 2 ** (s / 12.0)
                    xs.append(s)
                    ys.append(np.nanmax(v) * 1000.0 * c / (div * fs_) * k)
            a.plot(xs, ys, color=C[i], label="%s, detector path (p95 of 1-s "
                   "median)" % nm)
        # drift path: T=1 s (pipelength WINDOW), SNR 20, by row frequency
        pts = defaultdict(list)
        for r in dt:
            if r["T"] == 1.0 and r["snr"] == 20:
                pts[r["row_hz"]].append(r["err_warm"])
        fr = sorted(pts)
        sm = [12 * math.log2(x / 440.0) for x in fr]
        e = [2 * rms(pts[x]) for x in fr]
        for i, (div, nm) in enumerate(((2.0, "open"), (4.0, "stopped"))):
            ys = [ee * 1000.0 * c / (div * x) * k for ee, x in zip(e, fr)]
            a.plot(sm, ys, color=C[i], ls="", marker="o", ms=4,
                   label="%s, strobe drift path (2 x RMS, 1 s, SNR 20 dB)" % nm)
        # a real pipe wanders: 0.5 c rms wind wander leaves ~0.17 c RMS
        # after 1 s (drift_wind.csv), whatever the path
        wind = read("drift_wind.csv")
        if wind:
            ew = 2 * rms([r["err"] for r in wind
                          if r["jitter"] == 0.5 and r["T"] == 1.0])
            for i, (div, nm) in enumerate(((2.0, "open"), (4.0, "stopped"))):
                a.plot(semis, ew * 1000.0 * c / (div * f) * k, color=C[i],
                       ls=":", lw=1.4,
                       label="%s, any path, pipe wandering 0.5 c rms "
                       "(2 x %.2f c)" % (nm, ew / 2))
        a.axhline(0.1, color=INK, ls="--", lw=1)
        a.text(-57, 0.11, "display step 0.1 mm", fontsize=8)
        a.set_yscale("log")
        a.set_xlim(-58, 40)
        a.set_ylabel("length uncertainty, mm")
        c_ticks(a, -57, 39)
        a.set_xlabel("sounding pitch")
        a.legend(fontsize=8, loc="upper right")
        a.set_title("Q4  Meaningful mm resolution = measurement precision "
                    "(cents) x mm per cent", loc="left", fontsize=10)
        fig.tight_layout()
        save(fig, "q4_length_resolution.png")
    write_summary("q4", lines)


# ---------------------------------------------------------------- Q5
def q5():
    rows = read("detect_focus.csv")
    if not rows:
        return
    near = read("detect_focus_near.csv")
    sets = [(rows, "pitch.c as it is: strongest harmonic within "
                   "FOCUS_SPAN 550 c")]
    if near:
        sets.append((near, "variant: harmonic nearest to the expected "
                           "partial (variants.py, pitch_focusnear.c)"))
    feet = {1: "8'", 2: "4'", 3: "2 2/3'", 4: "2'", 5: "1 3/5'",
            6: "1 1/3'", 8: "1'", 16: "1/2'"}
    fig, axs = plt.subplots(len(sets), 1, figsize=(11, 3.3 * len(sets) + 0.8),
                            squeeze=False)
    cmap = ListedColormap([C[0], C[1], C[7], NONE_GREY])
    lines = []
    for n, (rs_, title) in enumerate(sets):
        a = axs[n, 0]
        ratios = sorted({r["ratio"] for r in rs_})
        semis = sorted({int(r["semi"]) for r in rs_})
        grid = np.full((len(ratios), len(semis)), 3.0)
        for r in rs_:
            i, j = ratios.index(r["ratio"]), semis.index(int(r["semi"]))
            cnt = [r["correct"], r["octave"], r["other"], r["none"]]
            if sum(cnt):
                grid[i, j] = int(np.argmax(cnt))
        lo, hi = semis[0], semis[-1]
        a.imshow(grid, aspect="auto", cmap=cmap, vmin=-0.5, vmax=3.5,
                 interpolation="nearest", origin="lower",
                 extent=(lo - 0.5, hi + 0.5, -0.5, len(ratios) - 0.5))
        a.grid(False)
        a.set_yticks(range(len(ratios)))
        a.set_yticklabels([feet.get(int(x), str(x)) for x in ratios])
        c_ticks(a, lo, hi)
        a.set_title(title, loc="left", fontsize=9)
        lines.append(title + ":")
        for i, rt in enumerate(ratios):
            # strobe row blank above 0.25*44100; detector partials < 9 kHz
            s_row = 12 * math.log2(11025.0 / rt / 440.0)
            s_det = 12 * math.log2(9000.0 / rt / 440.0)
            if s_row < hi + 0.5:
                a.plot([s_row, s_row], [i - 0.5, i + 0.5], color=INK, lw=2)
            if s_det < hi + 0.5:
                a.plot([s_det, s_det], [i - 0.5, i + 0.5], color="white",
                       lw=2, ls=":")
            bad = [note_name(s) for j, s in enumerate(semis)
                   if grid[i, j] != 0]
            lines.append("  %s: focus fails at %d notes: %s; detector "
                         "partial limit 9 kHz from %s, strobe row blank "
                         "from %s" % (feet.get(int(rt)), len(bad),
                                      compress(bad),
                                      note_name(math.ceil(s_det)),
                                      note_name(math.ceil(s_row))))
    from matplotlib.patches import Patch
    axs[-1, 0].legend(handles=[
        Patch(color=C[0], label="correct"),
        Patch(color=C[1], label="octave error"),
        Patch(color=C[7], label="other (a neighbouring partial)"),
        Patch(color=NONE_GREY, label="nothing"),
        plt.Line2D([], [], color=INK, lw=2,
                   label="strobe row blank above (11025 Hz)"),
        plt.Line2D([], [], color=INK2, lw=2, ls=":",
                   label="detector MAX_PARTIAL 9000 Hz")],
        ncol=3, fontsize=8, loc="upper left", bbox_to_anchor=(0, -0.14))
    axs[-1, 0].set_xlabel("key (8' pitch)")
    fig.suptitle("Q5  Focused partial in a full mixture (ranks 8' .. 1/2', "
                 "the others +-3 c): pitch_hint(ratio, key)", x=0.01,
                 ha="left", fontsize=10)
    fig.tight_layout()
    save(fig, "q5_focus_rows.png")
    write_summary("q5", lines)


def q5_alias():
    rows = read("detect_decim.csv")
    if not rows:
        return
    fig, a = plt.subplots(1, 1, figsize=(11, 3.4))
    lines = []
    hb = read("detect_decim_hb.csv") or []
    for i, (rs, lab) in enumerate((
            ([r for r in rows if r["bandlimited"] == 0],
             "reed (partials up to 20 kHz), pitch.c as it is"),
            ([r for r in rows if r["bandlimited"] == 1],
             "same reed with partials above 10.5 kHz removed"),
            ([r for r in hb if r["bandlimited"] == 0],
             "reed, variant: 63-tap half-band decimation"))):
        if not rs:
            continue
        lim = i
        x = [int(r["semi"]) for r in rs]
        y = [r["conf"] for r in rs]
        a.plot(x, y, color=C[lim], marker="o", ms=3, label=lab,
               ls=(":" if lim == 2 else "-"))
        miss = [note_name(int(r["semi"])) for r in rs
                if r["hz"] <= 0 or abs(1200 * math.log2(r["hz"] / r["f0"])) > 50]
        lines.append("%s: no/wrong estimate at %s" % (lab, compress(miss)))
    a.axhline(0.7, color=INK, ls="--", lw=1)
    a.text(x[0], 0.71, "MIN_CONF 0.7", fontsize=8, va="bottom")
    c_ticks(a, x[0], x[-1])
    a.set_ylabel("confidence (share of peak\nenergy on the harmonics)")
    a.set_xlabel("sounding pitch")
    a.legend(fontsize=8, loc="lower left")
    a.set_title("Q5  Aliases of the 2:1 decimation (pair average) cost the "
                "top octaves of reeds their estimate", loc="left",
                fontsize=10)
    fig.tight_layout()
    save(fig, "q5_decimation_alias.png")
    write_summary("q5alias", lines)


def main():
    q1()
    q2()
    q3()
    q3_beat()
    q3_trem()
    q3_level()
    q3_wind()
    q4()
    q5()
    q5_alias()
    for p in ("detect_cpu.csv", "drift_cpu.csv"):
        path = os.path.join(OUT, p)
        if os.path.exists(path):
            SUMMARY.setdefault("cpu", []).append(open(path).read().strip())
    with open(os.path.join(OUT, "summary.txt"), "w") as f:
        for k, v in SUMMARY.items():
            f.write("[%s]\n" % k)
            for l in v:
                f.write("  " + l + "\n")
    print("wrote summary.txt")


if __name__ == "__main__":
    main()
