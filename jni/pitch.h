#ifndef PITCH_H
#define PITCH_H

void pitch_init(int sample_rate);
/* feeds raw 16-bit samples; returns 1 when a new estimate was made, with
 * *hz = 0.0 when nothing clear enough was heard */
int pitch_feed(const short *samps, int len, double *hz, double *conf);
/* focus_ratio > 0: the partial the user focused on, as a multiple of the
 * note's fundamental (2.0 = 4' in organ terms), and expect_hz the note
 * now shown (0 = unknown).  The estimate is then that partial's peak
 * divided by focus_ratio.  0, 0 = plain detection */
void pitch_hint(double focus_ratio, double expect_hz);

#endif /* PITCH_H */
