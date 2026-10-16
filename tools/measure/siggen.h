/* siggen.h - synthetic organ pipe signals for the measurement drivers
 * (m_detect.c, m_drift.c).  Header only, deterministic (own RNG).
 *
 * A pipe is a set of exactly harmonic partials (pipes are phase locked,
 * no inharmonicity) with random start phases, an attack envelope that is
 * slower for low pipes, a short chiff (noise burst) during the attack,
 * optional tremulant (common FM + AM), and wind/room noise.  The whole sum
 * can go through a phone-microphone high-pass (4th-order Butterworth).
 *
 * Signal types (spectra, partial h = 1..):
 *   OPEN      open flue (Principal):      1/h^0.9, 16 partials
 *   STOPPED   stopped flue (Gedackt):     odd partials 1, .25, .08, .03,
 *                                         even ones at 2% (real pipes leak)
 *   REED      Trompete: weak fundamental, maximum at the 4th-5th, 30 partials
 *   NOFUND    open flue without its fundamental
 *   NOISY     open flue, white noise at SNR 10 dB (full band)
 *   PHONE     open flue through a phone mic: 24 dB/oct high-pass at 150 Hz
 *   STOPPHONE stopped flue (Subbass) through the phone mic
 *   NOISY0    open flue, white noise at SNR 0 dB
 *   WIND      open flue whose pitch wanders with the wind: 0.5 c rms,
 *             correlation time 0.1 s (an assumption, see FINDINGS.md)
 * (names for plots: plot.py SIG)
 */
#ifndef SIGGEN_H
#define SIGGEN_H

#include <math.h>
#include <stdio.h>
#include <string.h>

#ifndef M_PI
#define M_PI 3.14159265358979323846
#endif
#ifndef M_LN2
#define M_LN2 0.69314718055994530942
#endif

#define SG_RATE 44100
#define SG_MAXP 48		/* partials per pipe */
#define SG_MAXPIPES 8

enum { SIG_OPEN, SIG_STOPPED, SIG_REED, SIG_NOFUND, SIG_NOISY, SIG_PHONE,
	SIG_STOPPHONE, SIG_NOISY0, SIG_WIND, NSIG };

/* ---- RNG: xorshift64*, uniform and gaussian ---- */
static unsigned long long sg_rs = 88172645463325252ULL;
static void sg_seed(unsigned long long s) {
	sg_rs = s*2654435761ULL + 0x9E3779B97F4A7C15ULL;
	if (!sg_rs) sg_rs = 1;
}
static double sg_uni(void) {		/* [0,1) */
	sg_rs ^= sg_rs >> 12; sg_rs ^= sg_rs << 25; sg_rs ^= sg_rs >> 27;
	return ((sg_rs*2685821657736338717ULL) >> 11) * (1.0/9007199254740992.0);
}
static double sg_gauss(void) {
	double u = sg_uni() + 1e-300, v = sg_uni();
	return sqrt(-2.0*log(u))*cos(2.0*M_PI*v);
}

/* ---- one pipe ---- */
struct sg_pipe {
	double f;			/* fundamental, Hz */
	double amp;			/* scale of the partial amplitudes */
	int np;
	double a[SG_MAXP];		/* partial amplitudes, h = 1..np */
	double ph[SG_MAXP];		/* phases (cycles) */
	double t_attack;		/* envelope time constant, s */
	double onset;			/* s, when it starts to speak */
	double chiff;			/* chiff noise level re amp */
};

/* fills the partial amplitudes of a pipe of the given type, so that the
 * sum has an rms of about 1 (before amp) */
static void sg_spectrum(struct sg_pipe *p, int type)
{
	int h;
	double e = 0.0;
	memset(p->a, 0, sizeof p->a);
	switch (type) {
	case SIG_STOPPED:
	case SIG_STOPPHONE:
		p->np = 12;
		for (h = 1; h <= p->np; h++) {
			static const double odd[6] = { 1, .25, .08, .03, .015, .008 };
			p->a[h-1] = (h & 1) ? odd[h/2] : 0.02/h;
		}
		break;
	case SIG_REED:
		p->np = 30;
		for (h = 1; h <= p->np; h++) {
			double x = h/4.5;
			p->a[h-1] = x*exp(1.0 - x);
		}
		p->a[0] = 0.2;
		break;
	default:	/* OPEN, NOFUND, NOISY, PHONE */
		p->np = 16;
		for (h = 1; h <= p->np; h++) {
			p->a[h-1] = 1.0/pow(h, 0.9);
		}
		if (type == SIG_NOFUND) p->a[0] = 0.0;
		break;
	}
	for (h = 0; h < p->np; h++) e += 0.5*p->a[h]*p->a[h];
	e = sqrt(e);
	for (h = 0; h < p->np; h++) p->a[h] /= e;
}

static void sg_pipe_init(struct sg_pipe *p, double f, int type, double amp)
{
	int h;
	memset(p, 0, sizeof *p);
	p->f = f;
	p->amp = amp;
	sg_spectrum(p, type);
	for (h = 0; h < SG_MAXP; h++) p->ph[h] = sg_uni();
	/* low pipes speak slowly: about 3 periods, 15 ms .. 250 ms */
	p->t_attack = 3.0/f;
	if (p->t_attack < 0.015) p->t_attack = 0.015;
	if (p->t_attack > 0.25) p->t_attack = 0.25;
	p->chiff = 0.3;
}

/* ---- biquad (RBJ high-pass) ---- */
struct sg_bq { double b0, b1, b2, a1, a2, x1, x2, y1, y2; };
static void sg_hp(struct sg_bq *q, double fc, double Q)
{
	double w = 2.0*M_PI*fc/SG_RATE, c = cos(w), al = sin(w)/(2.0*Q);
	double a0 = 1.0 + al;
	memset(q, 0, sizeof *q);
	q->b0 = (1.0 + c)/2.0/a0; q->b1 = -(1.0 + c)/a0; q->b2 = q->b0;
	q->a1 = -2.0*c/a0; q->a2 = (1.0 - al)/a0;
}
static double sg_bq_run(struct sg_bq *q, double x)
{
	double y = q->b0*x + q->b1*q->x1 + q->b2*q->x2 - q->a1*q->y1 - q->a2*q->y2;
	q->x2 = q->x1; q->x1 = x; q->y2 = q->y1; q->y1 = y;
	return y;
}

/* ---- a scene: pipes + noise + tremulant + mic ---- */
struct sg_scene {
	int npipes;
	struct sg_pipe p[SG_MAXPIPES];
	double noise;		/* white noise rms, 16-bit units */
	double trem_rate;	/* Hz, 0 = off */
	double trem_fm;		/* cents, peak */
	double trem_am;		/* relative, peak */
	int phone;		/* 24 dB/oct high-pass at 150 Hz */
	struct sg_bq hp1, hp2;
	double jitter;		/* cents rms: slow random pitch wander (wind) */
	double jit_tau;		/* its correlation time, s */
	double jit;		/* AR(1) state, unit variance */
	long n;			/* samples made so far */
	double trem_ph;
};

static void sg_scene_init(struct sg_scene *s)
{
	memset(s, 0, sizeof *s);
}

static void sg_scene_ready(struct sg_scene *s)
{
	/* 4th-order Butterworth = Q 0.541 and 1.307 */
	sg_hp(&s->hp1, 150.0, 0.5412);
	sg_hp(&s->hp2, 150.0, 1.3066);
	s->trem_ph = sg_uni();
}

/* makes len samples */
static void sg_render(struct sg_scene *s, short *out, int len)
{
	int i, k, h;
	for (i = 0; i < len; i++, s->n++) {
		double t = s->n/(double)SG_RATE, v = 0.0;
		double fm = 1.0, am = 1.0;
		if (s->trem_rate > 0.0) {
			double tr = sin(2.0*M_PI*s->trem_ph);
			fm = pow(2.0, s->trem_fm*tr/1200.0);
			am = 1.0 + s->trem_am*tr;
			s->trem_ph += s->trem_rate/SG_RATE;
			if (s->trem_ph >= 1.0) s->trem_ph -= 1.0;
		}
		if (s->jitter > 0.0) {
			double a = exp(-1.0/(s->jit_tau*SG_RATE));
			s->jit = a*s->jit + sqrt(1.0 - a*a)*sg_gauss();
			fm *= 1.0 + s->jitter*s->jit*(M_LN2/1200.0);
			am *= 1.0 + 0.02*s->jit;
		}
		for (k = 0; k < s->npipes; k++) {
			struct sg_pipe *p = &s->p[k];
			double tt = t - p->onset, env, sum = 0.0;
			if (tt < 0.0) continue;
			env = 1.0 - exp(-tt/p->t_attack);
			for (h = 0; h < p->np; h++) {
				double fh = (h+1)*p->f*fm;
				if (fh < 0.45*SG_RATE && p->a[h] != 0.0) {
					sum += p->a[h]*sin(2.0*M_PI*p->ph[h]);
				}
				p->ph[h] += fh/SG_RATE;
				if (p->ph[h] >= 1.0) p->ph[h] -= floor(p->ph[h]);
			}
			v += p->amp*am*env*sum;
			if (p->chiff > 0.0 && tt < 2.0*p->t_attack) {
				v += p->amp*p->chiff*exp(-tt/p->t_attack)*sg_gauss();
			}
		}
		if (s->noise > 0.0) v += s->noise*sg_gauss();
		if (s->phone) v = sg_bq_run(&s->hp2, sg_bq_run(&s->hp1, v));
		if (v > 32767.0) v = 32767.0;
		if (v < -32767.0) v = -32767.0;
		out[i] = (short)lrint(v);
	}
}

/* a single pipe of the given type at f, rms level lvl (16-bit units),
 * starting at onset seconds; room/wind noise 50 dB below */
static void sg_scene_pipe(struct sg_scene *s, int type, double f, double lvl,
			double onset)
{
	sg_scene_init(s);
	s->npipes = 1;
	sg_pipe_init(&s->p[0], f, type, lvl);
	s->p[0].onset = onset;
	s->noise = lvl*pow(10.0, -50.0/20.0);
	if (type == SIG_NOISY) s->noise = lvl*pow(10.0, -10.0/20.0);
	if (type == SIG_NOISY0) s->noise = lvl;
	if (type == SIG_WIND) {
		s->jitter = 0.5;
		s->jit_tau = 0.1;
	}
	if ((type == SIG_PHONE) || (type == SIG_STOPPHONE)) s->phone = 1;
	sg_scene_ready(s);
}

/* note helpers: semitones from A4 */
static double sg_note_hz(int semi) { return 440.0*pow(2.0, semi/12.0); }
static void sg_note_name(int semi, char *out)
{
	static const char *nm[12] = { "A","A#","B","C","C#","D","D#","E","F",
					"F#","G","G#" };
	int k = semi + 48;		/* A0 = 0 */
	sprintf(out, "%s%d", nm[((k%12)+12)%12], (int)floor((k+9)/12.0));
}

#endif /* SIGGEN_H */
