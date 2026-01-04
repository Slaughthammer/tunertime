#ifndef __INTERFACE_H
#define __INTERFACE_H 1

#include <jni.h>

/* this had better be bigger than record_bufsz, and being too big is kind of
 * harmless (record_bufsz on my system is just over 4kB, or 2ksamps) */
#define SND_BUFSAMPS 32768

#define MAX_FFT_SIZE 32768
#define FFT_RANGE_MULT 65536	/* for normalized values in fft_yoffset et al */
#define FFT_SECONDS 5		/* 5 seconds on the screen at once */

#define NUM_STROBES 3


/* interface.c: */
extern int width;
extern int height;
#define COLOR_VALUE(r,g,b) (0xFF000000|((r)<<16)|((g)<<8)|(b))
#define ADDR_PIXEL(x,y) (&bitmap_ptr[(x)+((y)*width)])
#define DRAW_PIXEL(x,y,c) *ADDR_PIXEL((x),(y)) = (c)
void filled_rect(jint *bitmap_ptr, int x1, int y1, int x2, int y2, unsigned color);
void hline(jint *bitmap_ptr, int x1, int y, int x2, unsigned color);
void vline(jint *bitmap_ptr, int x, int y1, int y2, unsigned color);
void xor_hline(jint *bitmap_ptr, int x1, int x2, int y);
void xor_vline(jint *bitmap_ptr, int x, int y1, int y2);
#define SEVEN_CHARS 40
#define SEVEN_WID (width/SEVEN_CHARS)
void seven_seg(jint *bitmap_ptr, int x, int y, char c, unsigned color);

extern int sample_rate;
extern short snd_buf[SND_BUFSAMPS];
extern int snd_buflen;

void println(const char *fmt, ...);
double cents_to_freq(jobject this, jint cents, jint mult);
void set_labels(jobject this, char *labels[NUM_STROBES]);
void set_pianoname(jobject this, char *pianoname);


/* strobe.c: */
extern int base_cents;
extern int harm0_mult, harm1_mult, harm2_mult;

void strobe_init(void);
void strobe_color(const char *s);
void strobe_redraw(jint *bitmap_ptr);
void strobe_newdata(jint *bitmap_ptr);
void strobe_recalculate(jobject this);


/* fft.c: */
extern int fft_mode;
extern int fft_size;
extern int fft_width;			/* size of time window we are viewing */
extern int fft_height;			/* size of freq window we are viewing */
extern int fft_xoffset, fft_yoffset;	/* offset, out of FFT_RANGE_MULT */
extern int fft_grid_freq;

void fft_init(void);
void fft_recalculate(jobject this);
void fft_newdata(jint *bitmap_ptr);
void fft_redraw(jint *bitmap_ptr);


#endif /* __INTERFACE_H */
