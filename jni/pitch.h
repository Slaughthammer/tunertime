#ifndef PITCH_H
#define PITCH_H

void pitch_init(int sample_rate);
/* feeds raw 16-bit samples; returns 1 when a new estimate was made, with
 * *hz = 0.0 when nothing clear enough was heard */
int pitch_feed(const short *samps, int len, double *hz, double *conf);

#endif /* PITCH_H */
