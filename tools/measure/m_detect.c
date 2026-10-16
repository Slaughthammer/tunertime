/* m_detect.c - measurements of the note detector (jni/pitch.c).
 *
 *   m_detect map   > detect_map.csv     note x signal type: what comes out
 *                                       in steady state, and latency
 *   m_detect acc   > detect_acc.csv     cents error of the estimates and of
 *                                       length mode's 1 s median (pipelength)
 *   m_detect focus > detect_focus.csv   focused partial (pitch_hint) across
 *                                       the keyboard, per footage
 *   m_detect decim > detect_decim.csv   reed in the top octaves, with and
 *                                       without partials above 10.5 kHz
 *   m_detect cpu                       CPU cost per second of audio
 *
 * Build: gcc -O2 -Ijni -o m_detect tools/measure/m_detect.c jni/pitch.c -lm
 * All signals come from siggen.h; fixed seeds, so the output is the same
 * on every run.
 */
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>
#include "pitch.h"
#include "siggen.h"

#define BUF 1024		/* Listener.BUFSZ */
#define DET_AGREE 3		/* TunerTime.DET_AGREE */
#define LO_SEMI (-57)		/* C0, 32' C */
#define HI_SEMI 39		/* C8 */

struct est { double t, hz; };

/* runs secs of the scene through the detector; returns the estimates
 * (t = end of the buffer that produced it, seconds from the start) */
static int run(struct sg_scene *sc, double secs, struct est *e, int max)
{
	int n = (int)(secs*SG_RATE), i, ne = 0;
	short buf[BUF];
	pitch_init(SG_RATE);
	for (i = 0; i + BUF <= n; i += BUF) {
		double hz, conf;
		sg_render(sc, buf, BUF);
		if (pitch_feed(buf, BUF, &hz, &conf) && (ne < max)) {
			e[ne].t = (i + BUF)/(double)SG_RATE;
			e[ne].hz = hz;
			ne++;
		}
	}
	return ne;
}

/* 0 correct (within 50 c), 1 octave error, 2 other, 3 none */
static int category(double hz, double f0)
{
	if (hz <= 0.0) return 3;
	double c = 1200.0*log2(hz/f0);
	double oc = c - 1200.0*floor(c/1200.0 + 0.5);
	if (fabs(c) < 50.0) return 0;
	if (fabs(oc) < 50.0) return 1;
	return 2;
}

static int semi_of(double hz) { return (int)lround(12.0*log2(hz/440.0)); }

static void do_map(void)
{
	const double onset = 0.5, len = 3.5, steady = 1.0;
	struct est e[256];
	int semi, type;
	printf("semi,note,f0,type,n_steady,correct,octave,other,none,"
	       "lat_first,lat_follow,wrong_follow_first,est_per_s\n");
	for (semi = LO_SEMI; semi <= HI_SEMI; semi++) {
		for (type = 0; type < NSIG; type++) {
			struct sg_scene sc;
			double f0 = sg_note_hz(semi);
			int cnt[4] = { 0 }, ns = 0, i, ne;
			double lat_first = NAN, lat_follow = NAN;
			int det_semi = -9999, det_count = 0, wrong_first = 0;
			char nm[8];
			sg_seed(1000*semi + type + 7777);
			sg_scene_pipe(&sc, type, f0, 3000.0, onset);
			ne = run(&sc, onset + len, e, 256);
			for (i = 0; i < ne; i++) {
				double t = e[i].t - onset;
				int c = category(e[i].hz, f0);
				if (t >= steady) {
					cnt[c]++;
					ns++;
				}
				if (t < 0.0) continue;
				if ((c == 0) && isnan(lat_first)) lat_first = t;
				/* TunerTime.on_pitch: DET_AGREE in a row */
				if (e[i].hz > 0.0) {
					int k = semi_of(e[i].hz);
					if (k == det_semi) det_count++;
					else { det_semi = k; det_count = 1; }
					if (det_count >= DET_AGREE) {
						if (k == semi) {
							if (isnan(lat_follow))
								lat_follow = t;
						} else if (isnan(lat_follow)) {
							wrong_first = 1;
						}
					}
				} else {
					det_count = 0;
				}
			}
			sg_note_name(semi, nm);
			printf("%d,%s,%.3f,%d,%d,%d,%d,%d,%d,%.3f,%.3f,%d,%.2f\n",
				semi, nm, f0, type, ns, cnt[0], cnt[1], cnt[2],
				cnt[3], lat_first, lat_follow, wrong_first,
				ne/(onset + len));
		}
		fflush(stdout);
	}
}

static int cmpd(const void *a, const void *b)
{
	double x = *(const double *)a, y = *(const double *)b;
	return (x > y) - (x < y);
}
static double median(double *v, int n)
{
	qsort(v, n, sizeof *v, cmpd);
	return (n & 1) ? v[n/2] : 0.5*(v[n/2-1] + v[n/2]);
}

/* length mode's detector path (pipelength.update): median of the pitches
 * of the last WINDOW ms, folded to the nearest octave (stopped: also the
 * 3rd and 5th partial), spread = MAD; stable = n >= 5 && MAD < 1 c */
static void do_acc(void)
{
	static const int types[] = { SIG_OPEN, SIG_STOPPED, SIG_REED,
				     SIG_NOISY, SIG_PHONE, SIG_WIND };
	const double secs = 5.0, settle = 1.0, window = 1.0;
	struct est e[256];
	int semi, ti, oi;
	printf("semi,f0,type,offset,n_ok,n_all,err_mean,err_sd,"
	       "win_n,win_stable_frac,win_abs_err_mean,win_abs_err_p95,"
	       "stable_abs_err_max,win_mad_mean\n");
	for (semi = LO_SEMI; semi <= HI_SEMI; semi++) {
	for (ti = 0; ti < (int)(sizeof types/sizeof types[0]); ti++) {
	for (oi = 0; oi < 5; oi++) {
		int type = types[ti];
		double off = -40.0 + 20.0*oi + 3.7;	/* cents off the note */
		double fn = sg_note_hz(semi), f0 = fn*pow(2.0, off/1200.0);
		struct sg_scene sc;
		int ne, i, j, nok = 0, nall = 0, nw = 0, nst = 0;
		double s1 = 0, s2 = 0, werr = 0, stmax = 0, madsum = 0;
		double werrs[256];
		sg_seed(31*semi + 1000*type + oi + 99);
		sg_scene_pipe(&sc, type, f0, 3000.0, 0.0);
		ne = run(&sc, secs, e, 256);
		for (i = 0; i < ne; i++) {
			if (e[i].t < settle) continue;
			nall++;
			if (category(e[i].hz, f0) == 0) {
				double c = 1200.0*log2(e[i].hz/f0);
				nok++; s1 += c; s2 += c*c;
			}
			/* the window ending here */
			if (e[i].t < settle + window) continue;
			double c[256], d[256], med, mad, det = NAN;
			int n = 0;
			for (j = i; (j >= 0) && (e[i].t - e[j].t <= window); j--) {
				if (e[j].hz > 0.0)
					c[n++] = 1200.0*log2(e[j].hz/fn);
			}
			if (n == 0) continue;
			med = median(c, n);
			for (j = 0; j < n; j++) d[j] = fabs(c[j] - med);
			mad = median(d, n);
			{
				static const int op[] = { 1 }, sp[] = { 1, 3, 5 };
				const int *pp = (type == SIG_STOPPED) ? sp : op;
				int np = (type == SIG_STOPPED) ? 3 : 1, p;
				for (p = 0; p < np; p++) {
					double m = med - 1200.0*log2(pp[p]);
					double r = m - 1200.0*floor(m/1200.0 + 0.5);
					if (isnan(det) || fabs(r) < fabs(det)) det = r;
				}
			}
			double err = det - off;
			werrs[nw++] = fabs(err);
			werr += fabs(err);
			madsum += mad;
			if ((n >= 5) && (mad < 1.0)) {
				nst++;
				if (fabs(err) > stmax) stmax = fabs(err);
			}
		}
		double mean = nok ? s1/nok : NAN;
		double sd = (nok > 1) ? sqrt((s2 - s1*s1/nok)/(nok-1)) : NAN;
		double p95 = NAN;
		if (nw) {
			qsort(werrs, nw, sizeof *werrs, cmpd);
			p95 = werrs[(int)(0.95*(nw-1))];
		}
		printf("%d,%.3f,%d,%.1f,%d,%d,%.4f,%.4f,%d,%.3f,%.4f,%.4f,%.4f,%.4f\n",
			semi, f0, type, off, nok, nall, mean, sd, nw,
			nw ? nst/(double)nw : NAN, nw ? werr/nw : NAN, p95,
			nst ? stmax : NAN, nw ? madsum/nw : NAN);
	}
	}
	fflush(stdout);
	}
}

/* organ mixture: ranks 8', 4', 2 2/3', 2', 1 3/5', 1 1/3', 1', each an
 * open flue with 4 partials, each rank a few cents off; the user focuses
 * one footage (pitch_hint(ratio, note)); the estimate should be the key's
 * pitch (the focused rank's pitch / ratio) */
static void do_focus(void)
{
	static const double ratio[] = { 1, 2, 3, 4, 5, 6, 8, 16 };
	const int nr = 8;
	struct est e[256];
	int semi, r, k;
	printf("semi,f0,ratio,partial_hz,correct,octave,other,none\n");
	for (semi = -33; semi <= 27; semi++) {	/* C2 .. C7 */
		for (r = 0; r < nr; r++) {
			double f0 = sg_note_hz(semi);
			struct sg_scene sc;
			int cnt[4] = { 0 }, ne, i;
			sg_seed(semi*17 + r + 5);
			sg_scene_init(&sc);
			for (k = 0; k < nr; k++) {
				struct sg_pipe *p = &sc.p[sc.npipes];
				double fk = f0*ratio[k];
				if (fk > 0.45*SG_RATE) continue;
				sg_pipe_init(p, fk, SIG_OPEN, 1500.0/sqrt(1.0 + k));
				p->np = 4;
				/* the focused rank is right, the others 0..+-3 c */
				if (k != r) p->f *= pow(2.0, (sg_uni()*6.0 - 3.0)/1200.0);
				sc.npipes++;
			}
			sc.noise = 3.0;
			sg_scene_ready(&sc);
			pitch_hint(ratio[r], f0);
			ne = run(&sc, 2.5, e, 256);
			pitch_hint(0.0, 0.0);
			for (i = 0; i < ne; i++) {
				if (e[i].t < 1.0) continue;
				cnt[category(e[i].hz, f0)]++;
			}
			printf("%d,%.3f,%g,%.1f,%d,%d,%d,%d\n", semi, f0, ratio[r],
				f0*ratio[r], cnt[0], cnt[1], cnt[2], cnt[3]);
		}
		fflush(stdout);
	}
}

/* aliasing of the 2:1 decimation: a reed in the top octaves as it is,
 * and with its partials above 10.5 kHz removed (what a proper anti-alias
 * filter would do); the confidence of the last estimate */
static void do_decim(void)
{
	int semi, lim, i;
	printf("semi,f0,bandlimited,hz,conf\n");
	for (semi = 3; semi <= HI_SEMI; semi++) {
		for (lim = 0; lim < 2; lim++) {
			struct sg_scene sc;
			short b[BUF];
			double hz = 0, c = 0, lh = 0, lc = 0;
			sg_seed(semi + 4242);
			sg_scene_pipe(&sc, SIG_REED, sg_note_hz(semi), 3000.0, 0.0);
			for (i = 0; lim && (i < sc.p[0].np); i++) {
				if ((i+1)*sc.p[0].f > 10500.0) sc.p[0].a[i] = 0.0;
			}
			pitch_init(SG_RATE);
			for (i = 0; i < 64; i++) {
				sg_render(&sc, b, BUF);
				if (pitch_feed(b, BUF, &hz, &c)) {
					lh = hz;
					lc = c;
				}
			}
			printf("%d,%.3f,%d,%.3f,%.4f\n", semi, sg_note_hz(semi),
				lim, lh, lc);
		}
	}
}

static void do_cpu(void)
{
	const int secs = 20;
	short *buf = malloc(secs*SG_RATE*sizeof(short));
	struct sg_scene sc;
	int i;
	double hz, conf;
	clock_t t0;
	sg_seed(1);
	sg_scene_pipe(&sc, SIG_REED, 110.0, 3000.0, 0.0);
	sg_render(&sc, buf, secs*SG_RATE);
	double best = 1e9;
	int rep;
	for (rep = 0; rep < 3; rep++) {		/* best of 3 */
		pitch_init(SG_RATE);
		t0 = clock();
		for (i = 0; i + BUF <= secs*SG_RATE; i += BUF)
			pitch_feed(buf + i, BUF, &hz, &conf);
		double ms = 1000.0*(clock() - t0)/CLOCKS_PER_SEC/secs;
		if (ms < best) best = ms;
	}
	printf("pitch_feed_ms_per_s,%.3f\n", best);
	free(buf);
}

int main(int argc, char **argv)
{
	const char *m = (argc > 1) ? argv[1] : "";
	if (!strcmp(m, "map")) do_map();
	else if (!strcmp(m, "acc")) do_acc();
	else if (!strcmp(m, "focus")) do_focus();
	else if (!strcmp(m, "decim")) do_decim();
	else if (!strcmp(m, "cpu")) do_cpu();
	else {
		fprintf(stderr, "usage: m_detect map|acc|focus|decim|cpu\n");
		return 1;
	}
	return 0;
}
