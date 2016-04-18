#ifndef WINDOW_H
#define WINDOW_H

enum window_kind {
	wk_rectangle,	/* complementary/2.0 */
	wk_hamming,	/* complementary/1.076 */
	wk_blackman,	/* non-complementary */
	wk_bartlett,	/* complementary */
	wk_hanning,	/* complementary */
	wk_first = wk_rectangle,
	wk_last = wk_hanning
};
struct window_rec {
	int nsamps;
	double samps[1];
};
struct window_rec *window_init(enum window_kind kind, int nsamps);
void window_apply(struct window_rec *w, double *samps);
void window_free(struct window_rec *w);
char *window_name(enum window_kind kind);
double window_complement_factor(enum window_kind kind);

#endif /* WINDOW_H */
