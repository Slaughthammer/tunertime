/* pitch.c - finds the note being played, for auto note detection.
 *
 * Runs on the strobe thread alongside the strobes, so it has to be cheap
 * and must not touch the FFT-mode state in fft.c.  The input is decimated
 * 2:1 (the partials we care about are well below 11kHz), and every HOP new
 * samples an N-point FFT is taken over the last N.  At 44.1kHz that is a
 * 0.37s window with 2.7Hz bins, analysed about 11 times a second.
 *
 * Peaks are picked from the magnitude spectrum, then every peak divided by
 * 1..MAX_DIV is scored as a fundamental by how much peak energy lies on its
 * harmonics 1..MAX_HARM.  Lower harmonics weigh more, which keeps the octave
 * below from winning on even harmonics alone, while a missing fundamental
 * (low piano and organ pipes) is still found from its upper partials.  The
 * per-partial tolerance is wide enough for piano inharmonicity.
 */
#include <math.h>
#include <string.h>
#include "pitch.h"

#define N		8192
#define LGN		13
#define HOP		(N/4)
#define MAX_PK		16
#define MAX_DIV		4
#define MAX_HARM	8
#define TOL_CENTS	40.0
#define MIN_F0		26.0	/* a bit under A0 */
#define MAX_F0		4300.0	/* a bit over C8 */
#define MAX_PARTIAL	9000.0
#define MIN_RMS		60.0	/* 16-bit units; quieter input is ignored */
#define MIN_CONF	0.65	/* share of peak energy the winner explains;
				 * plucked strings on the phone mic score
				 * 0.7-0.98, decaying tails ~0.55-0.6 */
#define SINE_CONF	0.8	/* share a lone partial needs to stand alone */

static double fs;		/* sample rate after decimation */
static float ring[N];		/* decimated input, circular */
static int ring_pos;
static int fresh;		/* decimated samples since the last analysis */
static int odd;			/* waiting for the second sample of a pair */
static float pair;

static double re[N], im[N], win[N], mag[N/2];
static double tw_r[N/2], tw_i[N/2];
static int bitrev[N];

void
pitch_init(int sample_rate)
{
	int i, j, b;

	fs = sample_rate / 2.0;
	memset(ring, 0, sizeof ring);
	ring_pos = fresh = odd = 0;
	for (i = 0; i < N; i++) {
		win[i] = 0.5 - 0.5*cos(2.0*M_PI*i/N);	/* Hann */
		for (j = 0, b = 0; b < LGN; b++) {
			j |= ((i >> b) & 1) << (LGN-1-b);
		}
		bitrev[i] = j;
	}
	for (i = 0; i < N/2; i++) {
		tw_r[i] = cos(2.0*M_PI*i/N);
		tw_i[i] = -sin(2.0*M_PI*i/N);
	}
}

static void
fft(void)
{
	int len, i, j;
	for (i = 0; i < N; i++) {
		j = bitrev[i];
		if (i < j) {
			double t = re[i]; re[i] = re[j]; re[j] = t;
			t = im[i]; im[i] = im[j]; im[j] = t;
		}
	}
	for (len = 2; len <= N; len <<= 1) {
		int half = len/2, step = N/len;
		for (i = 0; i < N; i += len) {
			for (j = 0; j < half; j++) {
				double wr = tw_r[j*step], wi = tw_i[j*step];
				int a = i+j, b = a+half;
				double xr = re[b]*wr - im[b]*wi;
				double xi = re[b]*wi + im[b]*wr;
				re[b] = re[a]-xr; im[b] = im[a]-xi;
				re[a] += xr; im[a] += xi;
			}
		}
	}
}

struct peak { double f, m; };

/* collects the MAX_PK strongest local maxima that stand well above the
 * average level, with parabolic interpolation of the frequency */
static int
find_peaks(struct peak *pk)
{
	int k, n = 0, kmin = (int)(MIN_F0*N/fs), kmax = (int)(MAX_PARTIAL*N/fs);
	double level = 0.0;

	if (kmin < 2) kmin = 2;
	if (kmax > N/2-2) kmax = N/2-2;
	for (k = kmin; k <= kmax; k++) {
		level += mag[k];
	}
	level = 4.0 * level / (kmax-kmin+1);

	for (k = kmin; k <= kmax; k++) {
		double a = mag[k-1], b = mag[k], c = mag[k+1];
		if ((b <= level) || (b <= a) || (b < c)) {
			continue;
		}
		double la = log(a+1e-9), lb = log(b), lc = log(c+1e-9);
		double den = la - 2.0*lb + lc;
		double d = (den < 0.0) ? 0.5*(la-lc)/den : 0.0;
		struct peak p = { (k+d)*fs/N, b };
		/* insert, keeping pk[] sorted by magnitude, strongest first */
		int i = (n < MAX_PK) ? n++ : MAX_PK;
		while ((i > 0) && (pk[i-1].m < p.m)) {
			if (i < MAX_PK) pk[i] = pk[i-1];
			i--;
		}
		if (i < MAX_PK) pk[i] = p;
	}
	return n;
}

/* scores f0 against the peaks; *f0 is refined from the matched partials */
static double
score(struct peak *pk, int n, double *f0)
{
	double s = 0.0, w = 1.0, fsum = 0.0, fwt = 0.0;
	int h, i, matched = 0;
	double lim = pow(2.0, TOL_CENTS/1200.0);

	for (h = 1; h <= MAX_HARM; h++, w *= 0.85) {
		double t = h * *f0, best = 0.0, bestf = 0.0;
		if (t > MAX_PARTIAL) break;
		for (i = 0; i < n; i++) {
			double r = pk[i].f / t;
			if ((r < lim) && (r > 1.0/lim) && (pk[i].m > best)) {
				best = pk[i].m;
				bestf = pk[i].f;
			}
		}
		if (best > 0.0) {
			s += w*best;
			matched++;
			fsum += h*best * (bestf/h);	/* high partials resolve */
			fwt += h*best;			/* f0 more finely */
		}
	}
	if (matched < 2) {
		return 0.0;
	}
	*f0 = fsum/fwt;
	return s;
}

static double
analyse(double *conf)
{
	int i, n, d;
	double mean = 0.0, rms = 0.0, total = 0.0, best = 0.0, best_f = 0.0;
	struct peak pk[MAX_PK];

	for (i = 0; i < N; i++) {
		re[i] = ring[(ring_pos+i) % N];
		mean += re[i];
	}
	mean /= N;
	for (i = 0; i < N; i++) {
		double v = re[i]-mean;
		rms += v*v;
		re[i] = v*win[i];
		im[i] = 0.0;
	}
	*conf = 0.0;
	if (sqrt(rms/N) < MIN_RMS) {
		return 0.0;
	}
	fft();
	for (i = 0; i < N/2; i++) {
		mag[i] = sqrt(re[i]*re[i] + im[i]*im[i]);
	}
	n = find_peaks(pk);
	for (i = 0; i < n; i++) {
		total += pk[i].m;
	}
	for (i = 0; i < n; i++) {
		for (d = 1; d <= MAX_DIV; d++) {
			double f0 = pk[i].f/d;
			if ((f0 < MIN_F0) || (f0 > MAX_F0)) continue;
			double s = score(pk, n, &f0);
			if (s > best*1.0001) {
				best = s;
				best_f = f0;
			}
		}
	}
	if (total <= 0.0) {
		return 0.0;
	}
	if ((best == 0.0) && (pk[0].f >= MIN_F0) && (pk[0].f <= MAX_F0)) {
		/* no harmonic series: a lone strong partial (flute stop,
		 * tuning fork, top octave) is the fundamental itself */
		*conf = pk[0].m/total;
		return (*conf >= SINE_CONF) ? pk[0].f : 0.0;
	}
	/* best is weighted, so this undercounts a little; that's fine */
	*conf = best/total;
	return (*conf >= MIN_CONF) ? best_f : 0.0;
}

int
pitch_feed(const short *s, int len, double *hz, double *conf)
{
	int i, ret = 0;
	for (i = 0; i < len; i++) {
		if (!odd) {
			pair = s[i];
			odd = 1;
			continue;
		}
		odd = 0;
		ring[ring_pos] = 0.5f*(pair + s[i]);	/* crude low-pass */
		ring_pos = (ring_pos+1) % N;
		if (++fresh >= HOP) {
			fresh = 0;
			*hz = analyse(conf);
			ret = 1;
		}
	}
	return ret;
}
