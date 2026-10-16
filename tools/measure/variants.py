"""variants.py - writes what-if copies of the app's native code into
tools/measure/build/, so that a proposed change can be measured next to
the code as it is (run.sh builds and runs them).  The app code itself is
not touched.

  pitch_halfband.c  jni/pitch.c with the 2:1 decimation's pair average
                    replaced by a 63-tap half-band low-pass (Blackman
                    windowed sinc, cut-off fs/4): no aliases of partials
                    above 11 kHz in the 0-9 kHz the detector looks at
  pitch_focusnear.c jni/pitch.c whose focus() takes the harmonic nearest
                    to where the focused partial should be, instead of the
                    strongest one within FOCUS_SPAN (550 c, which reaches
                    the neighbouring partials from the 3rd partial up)
  strobe_onset.c    jni/strobe.c whose drift settling (DRIFT_SETTLE_TAUS)
                    restarts at every onset (the row's block amplitude
                    rising from below the gate, or more than doubling),
                    not only at a reset, so a pipe's attack transient is
                    not counted

python tools/measure/variants.py [build dir]
"""
import os
import sys

ROOT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "..")
OUT = sys.argv[1] if len(sys.argv) > 1 else os.path.join(
    os.path.dirname(os.path.abspath(__file__)), "build")


def patch(src, dst, pairs):
    with open(os.path.join(ROOT, "jni", src), newline="") as f:
        s = f.read()
    nl = "\r\n" if "\r\n" in s else "\n"
    s = s.replace("\r\n", "\n")
    for old, new in pairs:
        if s.count(old) != 1:
            sys.exit("variants.py: %s no longer matches:\n%s" % (src, old))
        s = s.replace(old, new)
    os.makedirs(OUT, exist_ok=True)
    with open(os.path.join(OUT, dst), "w", newline="") as f:
        f.write(s.replace("\n", nl))
    print("wrote", os.path.join(OUT, dst))


patch("pitch.c", "pitch_halfband.c", [
    ("static int odd;\t\t\t/* waiting for the second sample of a pair */\n"
     "static float pair;\n",
     "static int odd;\t\t\t/* waiting for the second sample of a pair */\n"
     "#define HB_TAPS 63\n"
     "static float hb_h[HB_TAPS], hb_x[HB_TAPS];\n"
     "static int hb_pos;\n"),
    ("\tring_pos = fresh = odd = 0;\n",
     "\tring_pos = fresh = odd = 0;\n"
     "\tmemset(hb_x, 0, sizeof hb_x);\n"
     "\thb_pos = 0;\n"
     "\tfor (i = 0; i < HB_TAPS; i++) {\n"
     "\t\tdouble m = i - (HB_TAPS-1)/2.0, x = 0.5*M_PI*m;\n"
     "\t\tdouble w = 0.42 - 0.5*cos(2.0*M_PI*i/(HB_TAPS-1)) +\n"
     "\t\t\t0.08*cos(4.0*M_PI*i/(HB_TAPS-1));\n"
     "\t\thb_h[i] = (float)(((m == 0.0) ? 0.5 : 0.5*sin(x)/x) * w);\n"
     "\t}\n"),
    ("\t\tif (!odd) {\n"
     "\t\t\tpair = s[i];\n"
     "\t\t\todd = 1;\n"
     "\t\t\tcontinue;\n"
     "\t\t}\n"
     "\t\todd = 0;\n"
     "\t\tring[ring_pos] = 0.5f*(pair + s[i]);\t/* crude low-pass */\n",
     "\t\thb_x[hb_pos] = s[i];\n"
     "\t\thb_pos = (hb_pos+1) % HB_TAPS;\n"
     "\t\tif (!odd) {\n"
     "\t\t\todd = 1;\n"
     "\t\t\tcontinue;\n"
     "\t\t}\n"
     "\t\todd = 0;\n"
     "\t\t{\n"
     "\t\t\tfloat y = 0.0f;\n"
     "\t\t\tint k;\n"
     "\t\t\tfor (k = 0; k < HB_TAPS; k++)\n"
     "\t\t\t\ty += hb_h[k]*hb_x[(hb_pos+k) % HB_TAPS];\n"
     "\t\t\tring[ring_pos] = y;\n"
     "\t\t}\n"),
])

patch("pitch.c", "pitch_focusnear.c", [
    ("\t\t\t    (pk[i].m > best)) {\n"
     "\t\t\t\tbest = pk[i].m;\n",
     "\t\t\t    (1.0/(1e-9 + fabs(log(pk[i].f/t))) > best)) {\n"
     "\t\t\t\t/* the partial nearest to where the focused one\n"
     "\t\t\t\t * should be, not the strongest in reach */\n"
     "\t\t\t\tbest = 1.0/(1e-9 + fabs(log(pk[i].f/t)));\n"),
])

patch("strobe.c", "strobe_onset.c", [
    ("\tint settle;\t\t/* samples still to ignore after a reset */\n",
     "\tint settle;\t\t/* samples still to ignore after a reset */\n"
     "\tint settle_len;\t\t/* ... and after an onset */\n"
     "\tdouble prev_amp;\n"
     "\tint prev_loud;\n"),
    ("\tdrift.settle = DRIFT_SETTLE_TAUS*20.0*sample_rate/(3.0*s->freq);\n",
     "\tdrift.settle = DRIFT_SETTLE_TAUS*20.0*sample_rate/(3.0*s->freq);\n"
     "\tdrift.settle_len = drift.settle;\n"),
    ("\tok = (amp >= DRIFT_MIN_AMP) && (amp >= DRIFT_REL_AMP*drift.peak);\n",
     "\tok = (amp >= DRIFT_MIN_AMP) && (amp >= DRIFT_REL_AMP*drift.peak);\n"
     "\t/* an onset (a key played, or the pipe getting much louder):\n"
     "\t * its transient rings at the row frequency, settle again */\n"
     "\tif (ok && (!drift.prev_loud || (amp > 2.0*drift.prev_amp))) {\n"
     "\t\tdrift.settle = drift.settle_len;\n"
     "\t}\n"
     "\tdrift.prev_loud = ok;\n"
     "\tdrift.prev_amp = amp;\n"),
])
