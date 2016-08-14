#include <stdio.h>
#include <stdlib.h>
#include <math.h>
#include <assert.h>
#include "window.h"

/* implementation of window functions for short term fourier transform */
/* compile with: gcc -o windowtest windowtest.c -DWINDOWTEST -lm
 *               (make windowtest)
 * to see which ones are complementary */

struct window_rec *
window_init(enum window_kind kind, int nsamps)
{
	int len;
	struct window_rec *ret;
	double *d;
	double nsm1 = nsamps-1;
	int i;

	if (kind == wk_rectangle) {
		return NULL;
	}

	len = nsamps * sizeof ret->samps[0];
	ret = malloc(len + sizeof *ret);
	if (!ret) {
		return NULL;
	}
	d = ret->samps;
#define FRAC (((double)i)/nsm1)

	ret->nsamps = nsamps;
	switch (kind) {
	case wk_hamming:
		for (i = 0; i < nsamps; i++) {
			d[i] = 0.54 - 0.46*cos(2.0*M_PI*FRAC);
		}
		break;
	case wk_blackman:
		for (i = 0; i < nsamps; i++) {
			d[i] = 0.42 - 0.5*cos(2.0*M_PI*FRAC) +
			       0.08*cos(4.0*M_PI*FRAC);
		}
		break;
	case wk_bartlett: {
		double no2 = nsm1/2.0;
		for (i = 0; i < nsamps; i++) {
			d[i] = 1.0 - fabs((((double)i) - no2)/no2);
		}
	}	break;
	case wk_hanning:
		for (i = 0; i < nsamps; i++) {
			d[i] = 0.5 - 0.5*cos(2.0*M_PI*FRAC);
		}
		break;
	default: assert(0);
	}
	return ret;
}

void
window_apply(struct window_rec *w, double *samps)
{
	double *d;
	int i, n;
	if (!w) {
		return;
	}
	d = w->samps;
	n = w->nsamps;
	for (i = 0; i < n; i++) {
		samps[i] *= d[i];
	}
}

void
window_free(struct window_rec *w)
{
	free(w);
}

char *
window_name(enum window_kind kind)
{
	switch (kind) {
		case wk_rectangle:	return "rectangle";
		case wk_hamming:	return "hamming";
		case wk_blackman:	return "blackman";
		case wk_bartlett:	return "bartlett";
		case wk_hanning:	return "hanning";
		default:		return "XXX";
	}
}

double
window_complement_factor(enum window_kind kind)
{
	switch (kind) {
		case wk_rectangle:	return 2.0;
		case wk_hamming:	return 1.08;
		case wk_blackman:	return 1.0;	/* not complementary! */
		case wk_bartlett:	return 1.0;
		case wk_hanning:	return 1.0;
		default:		return 1.0;
	}
}

#if WINDOWTEST
#define NS 256
int
main(void)
{
	enum window_kind k;
	for (k = wk_first; k <= wk_last; k++) {
		int i;
		struct window_rec *w = window_init(k, NS);
		double samps[NS];
		double samps2[NS];
		double lowest = 100.0;
		double highest = 0.0;
		double sqvarsum = 0.0;
		double avg = 0.0;

		for (i = 0; i < NS; i++) {
			samps[i] = 1.0;
		}

		window_apply(w, samps);

			/* overlap and add */
		for (i = 0; i < NS; i++) {
			int j = (i+NS/2)&(NS-1);
			samps2[i] = samps[i] + samps[j];
			avg += samps2[i];
		}
		avg /= 256.0;

		for (i = 0; i < NS; i++) {
			double s = samps2[i];
			if (s < lowest) lowest = s;
			if (s > highest) highest = s;
			sqvarsum += (s-avg)*(s-avg);
		}
		printf("kind=%s, range=[%f, %f], avg=%f, sd=%f\n",
				window_name(k), lowest, highest,
				avg, sqrt(sqvarsum / 256.0));
	}
	return 0;
}
#endif
