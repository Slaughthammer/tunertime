#ifndef __INTERFACE_H
#define __INTERFACE_H 1

#include <jni.h>

/* this had better be bigger than record_bufsz, and being too big is kind of
 * harmless (record_bufsz on my system is just over 4kB, or 2ksamps) */
#define SND_BUFSAMPS 32768

#define MAX_FFT_SIZE 32768
#define FFT_RANGE_MULT 65536	/* for normalized values in fft_x/yoffset etc */
#define MAX_PEAKS 100

#define MAX_STROBES 8
#define UNITS_PER_CENT 10	/* cents are in tenths (Java preset.UNITS_PER_CENT) */
	/* organ mode rows hold multiples of the 32' pitch, so 8' (the note
	 * itself) is ORGAN_DIV (Java parms.FOOTAGE_DIV) */
#define ORGAN_DIV 4


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
unsigned batlow(double t);
#define SEVEN_CHARS 40
#define SEVEN_WID (width/SEVEN_CHARS)
void seven_seg(jint *bitmap_ptr, int x, int y, char c, unsigned color);

extern int sample_rate;
extern short snd_buf[SND_BUFSAMPS];
extern int snd_buflen;

void println(const char *fmt, ...);
double cents_to_freq(jobject this, jint cents, jint mult);
void set_labels(jobject this, char *labels[MAX_STROBES]);
void set_pianoname(jobject this, char *pianoname);
void focus_drift(jobject this, double freq, double cycles, double secs,
		double buf_secs, double amp);


/* strobe.c: */
extern int base_cents;
extern int num_strobes;
extern int harm_mult[MAX_STROBES];
extern int organ_mode;
	/* highest strobe frequency in organ mode, as a fraction of the sample
	 * rate: four samples per period still make a readable pattern.
	 * Java asks for it with Strobe.organ_max_freq() */
#define ORGAN_MAX_FREQ 0.25

void strobe_init(void);
void strobe_focus(double ratio);
int strobe_drift(double *freq, double *cycles, double *secs);
int strobe_drift_amp(double *amp);
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


/* record.c: the last few seconds of input, for DebugRecorder.java */
void record_init(int rate);
void record_feed(const short *s, int n);


#endif /* __INTERFACE_H */
