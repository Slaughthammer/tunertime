/* bench test for jni/pitch.c: synthetic tones in, detected note out */
#include <math.h>
#include <stdio.h>
#include <stdlib.h>
#include <time.h>
#include "pitch.h"

#define SR 44100

static unsigned rng = 12345;
static double noise(void) {
	rng = rng*1103515245u + 12345u;
	return ((rng >> 8) & 0xffff) / 32768.0 - 1.0;
}

/* amps[h-1] for harmonics 1..nh; B = inharmonicity */
static double detect(double f0, const double *amps, int nh, double B,
			double level, double nlevel, double secs, double *conf)
{
	int n = (int)(secs*SR), i, h;
	short *buf = malloc(n*sizeof(short));
	double ph[16] = { 0 };
	for (i = 0; i < n; i++) {
		double v = 0.0;
		for (h = 1; h <= nh; h++) {
			double fh = h*f0*sqrt(1.0 + B*h*h);
			if (fh > SR/2.2) break;
			v += amps[h-1]*sin(ph[h-1]);
			ph[h-1] += 2.0*M_PI*fh/SR;
		}
		v = level*v + nlevel*noise();
		if (v > 32767) v = 32767;
		if (v < -32767) v = -32767;
		buf[i] = (short)v;
	}
	pitch_init(SR);
	double hz = 0.0, c = 0.0, last = 0.0, lastc = 0.0;
	for (i = 0; i + 1024 <= n; i += 1024) {
		if (pitch_feed(buf+i, 1024, &hz, &c)) {
			last = hz; lastc = c;
		}
	}
	free(buf);
	*conf = lastc;
	return last;
}

static const char *names[12] = { "A","A#","B","C","C#","D","D#","E","F","F#","G","G#" };

static int semis_from_a4(double hz) { return (int)lround(12.0*log2(hz/440.0)); }

static void name(int s, char *out) {
	int k = s + 48;			/* A0 = 0 */
	sprintf(out, "%s%d", names[((k%12)+12)%12], (k+9)/12);
}

int main(void)
{
	static const double full[8]   = { 1, .6, .4, .3, .2, .15, .1, .08 };
	static const double nofund[8] = { 0, 1, .8, .6, .4, .3, .2, .1 };
	static const double weak1[8]  = { .15, 1, .5, .4, .3, .2, .1, .05 };
	static const double pure[1]   = { 1 };
	struct { const char *what; const double *a; int nh; double B; double lvl, nl; } cases[] = {
		{ "harmonic",          full,   8, 0.0,    3000, 30 },
		{ "no fundamental",    nofund, 8, 0.0,    3000, 30 },
		{ "weak fundamental",  weak1,  8, 0.0,    3000, 30 },
		{ "piano B=4e-4",      full,   8, 4e-4,   3000, 30 },
		{ "sine",              pure,   1, 0.0,    3000, 30 },
		{ "noisy (SNR ~10dB)", full,   8, 0.0,    1500, 500 },
	};
	int nc = sizeof cases / sizeof cases[0], c, s, ok = 0, tot = 0;
	char want[8], got[8];

	for (c = 0; c < nc; c++) {
		int good = 0, cnt = 0;
		printf("%-18s", cases[c].what);
		for (s = -48; s <= 39; s += 3) {	/* A0 .. C8 */
			double f0 = 440.0*pow(2.0, s/12.0), conf;
			if (cases[c].a == nofund && f0 > 2000) continue;
			double hz = detect(f0, cases[c].a, cases[c].nh, cases[c].B,
					cases[c].lvl, cases[c].nl, 1.0, &conf);
			int g = (hz > 0) ? semis_from_a4(hz) : -999;
			cnt++;
			if (g == s) {
				good++;
			} else {
				name(s, want);
				if (g == -999) sprintf(got, "none");
				else name(g, got);
				printf(" [%s->%s]", want, got);
			}
		}
		printf("  %d/%d\n", good, cnt);
		ok += good; tot += cnt;
	}
	double conf;
	double hz = detect(440, full, 8, 0, 0, 30, 1.0, &conf);
	printf("silence+hiss       -> %s (conf %.2f)\n", hz > 0 ? "DETECTED (bad)" : "none (good)", conf);
	hz = detect(440, full, 8, 0, 0, 3000, 1.0, &conf);
	printf("loud white noise   -> %s (conf %.2f)\n", hz > 0 ? "DETECTED (bad)" : "none (good)", conf);

	/* cost: analyses per second of CPU */
	{
		int n = SR*10, i;
		short *buf = malloc(n*sizeof(short));
		for (i = 0; i < n; i++) buf[i] = (short)(3000*sin(2*M_PI*220.0*i/SR) + 30*noise());
		pitch_init(SR);
		struct timespec t0, t1;
		clock_gettime(CLOCK_MONOTONIC, &t0);
		double h2, c2;
		for (i = 0; i + 1024 <= n; i += 1024) pitch_feed(buf+i, 1024, &h2, &c2);
		clock_gettime(CLOCK_MONOTONIC, &t1);
		double ms = (t1.tv_sec-t0.tv_sec)*1e3 + (t1.tv_nsec-t0.tv_nsec)/1e6;
		printf("cost: %.1f ms CPU per 10 s of audio (%.2f%% of one core)\n", ms, ms/100.0);
		free(buf);
	}
	printf("TOTAL %d/%d\n", ok, tot);
	return 0;
}
