/* m_drift.c - measurements of the focused-row drift (jni/strobe.c), which
 * mixture mode (mixture.java, MEASURE_SECONDS) and length mode
 * (pipelength.java) read.
 *
 *   m_drift time  > drift_time.csv   error vs measurement time, per
 *                                    footage, key and SNR (single pipe)
 *   m_drift beat  > drift_beat.csv   a second pipe 1-3 c off on the same
 *                                    partial (mixture: the 2' rank's 2nd
 *                                    partial under the 1' rank)
 *   m_drift trem  > drift_trem.csv   tremulant on
 *   m_drift level > drift_level.csv  input level sweep (quiet partials)
 *   m_drift wind  > drift_wind.csv   the pipe's pitch wandering with the wind
 *   m_drift cpu                      CPU cost of the strobe rows
 *
 * Build: gcc -O2 -Itools/measure/jni_stub -Ijni -Itools/measure \
 *            -o m_drift tools/measure/m_drift.c jni/strobe.c -lm
 *
 * The measurement is emulated like mixture.feed(): one strobe_drift()
 * reading per 1024-sample buffer (Listener.BUFSZ), cycles and usable
 * seconds summed until the usable seconds reach T, then
 * cents = 1200*log2((f + cycles/secs)/f).
 */
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>
#include "interface.h"
#include "siggen.h"

/* ---- stand-ins for interface.c ---- */
int width = 1080, height = 1400, sample_rate = SG_RATE;
short snd_buf[SND_BUFSAMPS];
int snd_buflen;
void filled_rect(jint *b, int x1, int y1, int x2, int y2, unsigned c) {}
void hline(jint *b, int x1, int y, int x2, unsigned c) {}
void vline(jint *b, int x, int y1, int y2, unsigned c) {}
void xor_hline(jint *b, int x1, int x2, int y) {}
void xor_vline(jint *b, int x, int y1, int y2) {}
unsigned batlow(double t) { return 0; }
void seven_seg(jint *b, int x, int y, char c, unsigned color) {}
void println(const char *fmt, ...) {}
double cents_to_freq(jobject t, jint cents, jint mult) {
	return 440.0*pow(2.0, (cents - 100*12*UNITS_PER_CENT*4)/
			(1200.0*UNITS_PER_CENT))*mult;
}
void set_labels(jobject t, char *l[MAX_STROBES]) {}
void set_pianoname(jobject t, char *p) {}
void focus_drift(jobject t, double f, double c, double s, double b) {}

static jint *bitmap;

#define BUF 1024
#define MAXBUF 2048

/* organ footages as multiples of the 32' pitch (harm_mult) */
static const int foot_mult[] = { 1, 2, 4, 8, 12, 32 };
static const char *foot_name[] = { "32'", "16'", "8'", "4'", "2 2/3'", "1'" };
#define NFOOT 6

static double b_cyc[MAXBUF], b_sec[MAXBUF];
static int nbuf;
static double row_freq;

/* sets the rows (organ mode, footages above), key semi (from A4), focus
 * on the row with harm_mult fm; renders secs of the scene through the
 * strobes; per-buffer drift in b_cyc/b_sec.  returns 0 if the row is
 * blank (above ORGAN_MAX_FREQ) */
static int sim(struct sg_scene *sc, int semi, int fm, double secs)
{
	int i, nb = (int)(secs*SG_RATE/BUF);
	organ_mode = 1;
	num_strobes = NFOOT;
	for (i = 0; i < NFOOT; i++) harm_mult[i] = foot_mult[i];
	base_cents = 48000 + semi*100*UNITS_PER_CENT;
	strobe_init();
	strobe_recalculate(0);
	strobe_focus(fm/(double)ORGAN_DIV);
	nbuf = 0;
	if (nb > MAXBUF) nb = MAXBUF;
	for (i = 0; i < nb; i++) {
		double f, c, s;
		sg_render(sc, snd_buf, BUF);
		snd_buflen = BUF;
		strobe_newdata(bitmap);
		if (!strobe_drift(&f, &c, &s)) return 0;
		row_freq = f;
		b_cyc[i] = c;
		b_sec[i] = s;
		nbuf++;
	}
	return 1;
}

/* mixture.feed from buffer b0 on: cents measured after T usable seconds,
 * *wall = seconds from b0 to the end; NaN if never reached */
static double measure(int b0, double T, double *wall)
{
	double c = 0.0, s = 0.0;
	int b;
	for (b = b0; b < nbuf; b++) {
		c += b_cyc[b];
		s += b_sec[b];
		if (s >= T) {
			*wall = (b + 1 - b0)*BUF/(double)SG_RATE;
			return 1200.0*log2((row_freq + c/s)/row_freq);
		}
	}
	*wall = NAN;
	return NAN;
}

static double row_hz(int semi, int fm)
{
	return 440.0*pow(2.0, semi/12.0)*fm/ORGAN_DIV;
}

static const int keys[] = { -33, -21, -9, 3, 15, 27 };	/* C2..C7 */
#define NKEYS 6

static void do_time(void)
{
	static const double Ts[] = { 0.25, 0.5, 0.75, 1.0, 1.5, 2.0, 3.0, 4.0 };
	static const double snrs[] = { 40, 20, 10, 0 };
	const int nT = 8, nseed = 8;
	const double warm = 2.5;
	int fi, ki, si, seed, t;
	printf("footage,mult,semi,row_hz,snr,seed,off,T,err_cold,wall_cold,"
	       "err_warm\n");
	for (fi = 0; fi < NFOOT; fi++)
	for (ki = 0; ki < NKEYS; ki++)
	for (si = 0; si < 4; si++)
	for (seed = 0; seed < nseed; seed++) {
		int fm = foot_mult[fi], semi = keys[ki];
		double fr = row_hz(semi, fm);
		double off, lvl = 3000.0;
		struct sg_scene sc;
		sg_seed(seed*1009 + fi*101 + ki*13 + si);
		off = sg_uni()*40.0 - 20.0;
		sg_scene_init(&sc);
		sc.npipes = 1;
		sg_pipe_init(&sc.p[0], fr*pow(2.0, off/1200.0), SIG_OPEN, lvl);
		sc.p[0].onset = 0.05;
		sc.noise = lvl*pow(10.0, -snrs[si]/20.0);
		sg_scene_ready(&sc);
		if (!sim(&sc, semi, fm, warm + 4.5)) {
			continue;	/* blank row */
		}
		for (t = 0; t < nT; t++) {
			double wc, ww;
			double ec = measure(0, Ts[t], &wc) - off;
			double ew = measure((int)(warm*SG_RATE/BUF), Ts[t], &ww) - off;
			printf("%s,%d,%d,%.3f,%g,%d,%.3f,%g,%.5f,%.3f,%.5f\n",
				foot_name[fi], fm, semi, fr, snrs[si], seed, off,
				Ts[t], ec, wc, ew);
		}
		fflush(stdout);
	}
}

/* two pipes on the focused partial: the rank tuned (A, off c) and a
 * second one (B) delta c above it with relative amplitude r.  as in a
 * mixture: A = the 1' rank's fundamental, B = the 2' rank's 2nd partial */
static void do_beat(void)
{
	static const double deltas[] = { 1, 2, 3 };
	static const double rs[] = { 0.2, 0.5, 0.7, 0.85, 1.0, 1.2, 1.5, 2.0, 5.0 };
	const int nr = sizeof rs / sizeof rs[0];
	static const int bkeys[] = { -21, 3 };	/* C3, C5 */
	const double warm = 2.5;
	const int nseed = 8;
	int ki, di, ri, seed, t;
	printf("semi,row_hz,delta,r,seed,T,err\n");
	for (ki = 0; ki < 2; ki++)
	for (di = 0; di < 3; di++)
	for (ri = 0; ri < nr; ri++)
	for (seed = 0; seed < nseed; seed++) {
		int fm = 32, semi = bkeys[ki];
		double fr = row_hz(semi, fm), off = 5.0;
		struct sg_scene sc;
		sg_seed(seed*7919 + ki*31 + di*7 + ri);
		sg_scene_init(&sc);
		sc.npipes = 2;
		sg_pipe_init(&sc.p[0], fr*pow(2.0, off/1200.0), SIG_OPEN, 600.0);
		sg_pipe_init(&sc.p[1], 0.5*fr*pow(2.0, (off + deltas[di])/1200.0),
				SIG_OPEN, 2000.0);
		/* B's 2nd partial = r * A's fundamental */
		sc.p[1].amp = rs[ri]*sc.p[0].amp*sc.p[0].a[0]/sc.p[1].a[1];
		sc.noise = 5.0;
		sg_scene_ready(&sc);
		if (!sim(&sc, semi, fm, warm + 10.5)) continue;
		for (t = 1; t <= 40; t++) {
			double w, T = 0.25*t;
			double e = measure((int)(warm*SG_RATE/BUF), T, &w) - off;
			printf("%d,%.3f,%g,%g,%d,%g,%.5f\n", semi, fr, deltas[di],
				rs[ri], seed, T, e);
		}
		fflush(stdout);
	}
}

/* tremulant: common FM (+-fm c) and AM on the whole pipe */
static void do_trem(void)
{
	static const double rates[] = { 4.5, 5.5, 6.5 };
	static const int tm[] = { 4, 32 };	/* 8', 1' (rows of sim()) */
	const double warm = 2.5, fmc = 10.0, amr = 0.25;
	const int nseed = 8, semi = -9;		/* C4 */
	int ri, mi, seed, t;
	printf("rate,mult,row_hz,fm_c,seed,T,err\n");
	for (ri = 0; ri < 3; ri++)
	for (mi = 0; mi < 2; mi++)
	for (seed = 0; seed < nseed; seed++) {
		double fr = row_hz(semi, tm[mi]), off = -3.0;
		struct sg_scene sc;
		sg_seed(seed*313 + ri*7 + mi);
		sg_scene_init(&sc);
		sc.npipes = 1;
		sg_pipe_init(&sc.p[0], fr*pow(2.0, off/1200.0), SIG_OPEN, 3000.0);
		sc.noise = 3.0;
		sc.trem_rate = rates[ri];
		sc.trem_fm = fmc;
		sc.trem_am = amr;
		sg_scene_ready(&sc);
		if (!sim(&sc, semi, tm[mi], warm + 4.5)) continue;
		for (t = 5; t <= 80; t++) {
			double w, T = 0.05*t;
			double e = measure((int)(warm*SG_RATE/BUF), T, &w) - off;
			printf("%g,%d,%.3f,%g,%d,%g,%.5f\n", rates[ri], tm[mi], fr,
				fmc, seed, T, e);
		}
		fflush(stdout);
	}
}

/* wind: the pipe's pitch wanders slowly (siggen.h jitter, correlation
 * time 0.1 s); error against the long-term mean pitch */
static void do_wind(void)
{
	static const double Ts[] = { 0.25, 0.5, 0.75, 1.0, 1.5, 2.0, 3.0, 4.0 };
	static const double jit[] = { 0.25, 0.5, 1.0 };
	static const int wm[] = { 1, 4, 32 };		/* 32', 8', 1' */
	static const int wk[] = { -33, -9, 15 };	/* C2 C4 C6 */
	const double warm = 2.5;
	const int nseed = 12;
	int ji, mi, ki, seed, t;
	printf("jitter,mult,semi,row_hz,seed,T,err\n");
	for (ji = 0; ji < 3; ji++)
	for (mi = 0; mi < 3; mi++)
	for (ki = 0; ki < 3; ki++)
	for (seed = 0; seed < nseed; seed++) {
		double fr = row_hz(wk[ki], wm[mi]), off = 4.0;
		struct sg_scene sc;
		if (fr > ORGAN_MAX_FREQ*SG_RATE) continue;
		sg_seed(seed*211 + ji*17 + mi*5 + ki);
		sg_scene_init(&sc);
		sc.npipes = 1;
		sg_pipe_init(&sc.p[0], fr*pow(2.0, off/1200.0), SIG_OPEN, 3000.0);
		sc.noise = 3000.0*pow(10.0, -30.0/20.0);
		sc.jitter = jit[ji];
		sc.jit_tau = 0.1;
		sg_scene_ready(&sc);
		if (!sim(&sc, wk[ki], wm[mi], warm + 4.5)) continue;
		for (t = 0; t < 8; t++) {
			double w;
			double e = measure((int)(warm*SG_RATE/BUF), Ts[t], &w) - off;
			printf("%g,%d,%d,%.3f,%d,%g,%.5f\n", jit[ji], wm[mi],
				wk[ki], fr, seed, Ts[t], e);
		}
		fflush(stdout);
	}
}

/* level: the pipe's rms from 3 to 10000 (16-bit units), mic self-noise
 * 2 units rms; T = 2 s; usable share of the time and the error */
static void do_level(void)
{
	static const int lk[] = { -33, -9, 15 };	/* C2 C4 C6, 8' row */
	const double warm = 2.5;
	const int nseed = 8;
	int ki, li, seed;
	printf("semi,row_hz,level,seed,err,wall\n");
	for (ki = 0; ki < 3; ki++)
	for (li = 0; li <= 14; li++)
	for (seed = 0; seed < nseed; seed++) {
		double lvl = 3.0*pow(10.0, li*0.25), fr = row_hz(lk[ki], 4);
		double off, w, e;
		struct sg_scene sc;
		sg_seed(seed*77 + li*3 + ki);
		off = sg_uni()*20.0 - 10.0;
		sg_scene_init(&sc);
		sc.npipes = 1;
		sg_pipe_init(&sc.p[0], fr*pow(2.0, off/1200.0), SIG_OPEN, lvl);
		sc.noise = 2.0;
		sg_scene_ready(&sc);
		if (!sim(&sc, lk[ki], 4, warm + 8.0)) continue;
		e = measure((int)(warm*SG_RATE/BUF), 2.0, &w) - off;
		printf("%d,%.3f,%.2f,%d,%.5f,%.3f\n", lk[ki], fr, lvl, seed, e, w);
		fflush(stdout);
	}
}

static void do_cpu(void)
{
	const int secs = 10, nb = secs*SG_RATE/BUF;
	int rows, i;
	for (rows = 1; rows <= 8; rows++) {
		struct sg_scene sc;
		clock_t t0;
		sg_seed(3);
		sg_scene_pipe(&sc, SIG_REED, 110.0, 3000.0, 0.0);
		organ_mode = 1;
		num_strobes = rows;
		for (i = 0; i < rows; i++) harm_mult[i] = 1 << (i % 6);
		base_cents = 48000 - 24*1000;
		strobe_init();
		strobe_recalculate(0);
		strobe_focus(1.0/ORGAN_DIV);
		/* the signal is made beforehand: only strobe time counts */
		short *all = malloc((size_t)nb*BUF*sizeof(short));
		sg_render(&sc, all, nb*BUF);
		double best = 1e9;
		int rep;
		for (rep = 0; rep < 3; rep++) {		/* best of 3 */
			t0 = clock();
			for (i = 0; i < nb; i++) {
				memcpy(snd_buf, all + (size_t)i*BUF,
					BUF*sizeof(short));
				snd_buflen = BUF;
				strobe_newdata(bitmap);
			}
			double ms = 1000.0*(clock() - t0)/CLOCKS_PER_SEC/secs;
			if (ms < best) best = ms;
		}
		printf("strobe_%d_rows_ms_per_s,%.3f\n", rows, best);
		free(all);
	}
}

int main(int argc, char **argv)
{
	const char *m = (argc > 1) ? argv[1] : "";
	bitmap = calloc((size_t)width*height, sizeof(jint));
	strobe_color("batlow");
	if (!strcmp(m, "time")) do_time();
	else if (!strcmp(m, "beat")) do_beat();
	else if (!strcmp(m, "trem")) do_trem();
	else if (!strcmp(m, "level")) do_level();
	else if (!strcmp(m, "wind")) do_wind();
	else if (!strcmp(m, "cpu")) do_cpu();
	else {
		fprintf(stderr, "usage: m_drift time|beat|trem|level|wind|cpu\n");
		return 1;
	}
	return 0;
}
