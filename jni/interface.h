#ifndef __INTERFACE_H
#define __INTERFACE_H 1

#include <jni.h>

/* this had better be bigger than record_bufsz, and being too big is kind of
 * harmless (record_bufsz on my system is just over 4kB, or 2ksamps) */
#define SND_BUFSAMPS 32768

#define MAX_FFT_SIZE 32768
#define FFT_RANGE_MULT 65536	/* for normalized values in fft_offset et al */
#define FFT_SECONDS 5		/* 5 seconds on the screen at once */

#define NUM_STROBES 3


/* interface.c: */
extern int width;
extern int height;
void filled_rect(jint *bitmap_ptr, int x1, int y1, int x2, int y2, unsigned color);
void hline(jint *bitmap_ptr, int x1, int y, int x2, unsigned color);
void vline(jint *bitmap_ptr, int x, int y1, int y2, unsigned color);
void xor_hline(jint *bitmap_ptr, int x1, int x2, int y);
void xor_vline(jint *bitmap_ptr, int x, int y1, int y2);
void seven_seg(jint *bitmap_ptr, int x, int y, char c, unsigned color);
#define COLOR_VALUE(r,g,b) (0xFF000000|((r)<<16)|((g)<<8)|(b))
#define ADDR_PIXEL(x,y) (&bitmap_ptr[(x)+((y)*width)])
#define DRAW_PIXEL(x,y,c) *ADDR_PIXEL((x),(y)) = (c)

extern int sample_rate;
extern short snd_buf[SND_BUFSAMPS];
extern int snd_ofs;

void println(const char *fmt, ...);
double cents_to_freq(jint cents, jint mult);
void set_labels(jobject this, char *labels[NUM_STROBES]);


/* strobe.c: */
extern int base_cents;
extern int harm1_mult, harm2_mult;

void strobe_newdata(jint *bitmap_ptr);
void strobe_recalculate(jobject this);


/* fft.c: */
extern int fft_mode;
extern int fft_size;
extern int fft_width;			/* size of freq window we are viewing */
extern int fft_offset;			/* offset, out of FFT_RANGE_MULT */
extern int fft_grid_freq;

void init_fft(void);
void fft_recalculate(jobject this);
void fft_newdata(jint *bitmap_ptr);
void fft_mode_changed(jint *bitmap_ptr);


#endif /* __INTERFACE_H */
