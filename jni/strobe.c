#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <math.h>
#include <assert.h>
#include <jni.h>

/* this had better be bigger than record_bufsz, and being too big is kind of
 * harmless (record_bufsz on my system is just over 4kB, or 2ksamps) */
#define SND_BUFSAMPS 32768

#define MAX_FFT_SIZE 32768
#define FFT_RANGE_MULT 65536	/* for normalized values in fft_offset et al */
#define FFT_SECONDS 5		/* 5 seconds on the screen at once */

#define NOTES_NUM 76
#define NOTES_LOWEST 12

#define NUM_STROBES 3

struct strobe_rec {
		/* location within bitmap */
	int height;
	int y;

		/* center frequency */
	double freq;
		/* filter coefficients: */
	double a0,a1,a2;
	double b1,b2;
		/* filter history: */
	double x1,x2;
	double y1,y2;
		/* filter output: */
	signed short filtered[SND_BUFSAMPS];

	unsigned msamps_per_col;        /* milisamples per column */
	int curr_samp;                  /* index into snd_buf/filtered */
	int prev_pos;                   /* position of last strobe point */
	unsigned samp_max;              /* highest amplitude seen so far */
	int multiplier;                 /* horizontal scale */
	int last_cleared_col;
};

/* for NOTES_NUM parallel note filters (starting at note NOTES_LOWEST) which are
 * used to estimate the currently playing note */
struct note_filter_rec {
	double freq;
	int fft_bin_lo, fft_bin_hi;	/* range of fft bins covered */
	double output;
};


static int sample_rate;
static int base_cents;
static int harm1_mult, harm2_mult;
static int width;
static int height;
static struct strobe_rec strobes[NUM_STROBES];
static short snd_buf[SND_BUFSAMPS];
static int snd_ofs;

static double fft_r[MAX_FFT_SIZE];
static double fft_i[MAX_FFT_SIZE];
static int nf_curr_samp = 0;			/* input */
static int nf_curr_ridx = 0;			/* output */
static double fft_sr[32];
static double fft_si[32];

static int fft_mode = 0;
static int fft_size = 8192;
static int fft_width = FFT_RANGE_MULT;	/* size of freq window we are viewing */
static int fft_offset = 0;		/* offset, out of FFT_RANGE_MULT */
static int fft_grid_freq = 0;
static int fft_y = 0;
static int fft_legend_height = 0;

static jint *bitmap_ptr;
#define COLOR_VALUE(r,g,b) (((r)<<16)|((g)<<8)|(b))
#define ADDR_PIXEL(x,y) (&bitmap_ptr[(x)+((y)*width)])
#define DRAW_PIXEL(x,y,c) *ADDR_PIXEL((x),(y)) = (c)

static void println(const char *fmt, ...);


static JNIEnv *env;

static jclass cl_system;
static jclass cl_printstream;
static jclass cl_string;
static jclass cl_strobe;
static jclass cl_tunertime;
static jclass cl_audiorecord;

static jmethodID mid_println;
static jmethodID mid_st_do_draw;
static jmethodID mid_st_time_to_die;
static jmethodID mid_tt_cents_to_freq;
static jmethodID mid_ar_getsamplerate;
static jmethodID mid_ar_read;

static jfieldID fid_out;
static jfieldID fid_st_cents;
static jfieldID fid_st_harm1_mult;
static jfieldID fid_st_harm2_mult;
static jfieldID fid_st_cents_changed;
static jfieldID fid_st_width;
static jfieldID fid_st_height;
static jfieldID fid_st_surface_changed;
static jfieldID fid_st_recorder;
static jfieldID fid_st_record_bufsz;
static jfieldID fid_st_labels;
static jfieldID fid_st_ui;
static jfieldID fid_st_fft_changed;
static jfieldID fid_st_fft_mode;
static jfieldID fid_st_fft_size;
static jfieldID fid_st_fft_width;
static jfieldID fid_st_fft_offset;
static jfieldID fid_st_fft_grid_freq;
static jfieldID fid_st_paused;

static jintArray bitmap;



static void
filter_recalc(struct strobe_rec *s)
{
	double R,K;
	double R2;
	double c;
	double freq = s->freq;
	double bandwidth = freq/20.0;

	freq /= ((double)sample_rate);
	bandwidth /= ((double)sample_rate);

	c = cos(2.0*M_PI*freq);
	R = 1.0-(3.0*bandwidth);
	R2 = R*R;
	K = ((1.0-(2.0*R*c))+R2)/(2.0-(2.0*c));

	s->a0 = 1.0-K;
	s->a1 = 2.0*(K-R)*c;
	s->a2 = R2-K;
	s->b1 = 2.0*R*c;
	s->b2 = -R2;

	s->x1 = s->x2 = s->y1 = s->y2 = 0.0;
}


static void
strobe_recalc(struct strobe_rec *s)
{
	filter_recalc(s);

	s->msamps_per_col = (sample_rate*1000) / s->freq;

	s->multiplier = (s->freq + width) / width;
	if (s->multiplier < 1) {
		s->multiplier = 1;
	}
}

static void
strobe_filter(struct strobe_rec *s)
{
	double x1,x2,y0,y1,y2;
	double a0,a1,a2,b1,b2;
	int i;

	a0 = s->a0; a1 = s->a1; a2 = s->a2; b1 = s->b1; b2 = s->b2;
	x1 = s->x1; x2 = s->x2; y1 = s->y1; y2 = s->y2;

	i = s->curr_samp % SND_BUFSAMPS;
	while (i != snd_ofs) {
		double in = snd_buf[i];
		y0 = ((a0*in) + (a1*x1) + (a2*x2) + (b1*y1) + (b2*y2));
		x2 = x1;
		x1 = in;
		y2 = y1;
		y1 = y0;
		s->filtered[i] = y0;
		i = (i+1)%SND_BUFSAMPS;
	}

	s->x1 = x1; s->x2 = x2; s->y1 = y1; s->y2 = y2;
}

static void
filled_rect(int x1, int y1, int x2, int y2, unsigned color)
{
	int x,y;
	for (y = y1; y < y2; y++) {
		for (x = x1; x < x2; x++) {
			DRAW_PIXEL(x, y, color);
		}
	}
}

static void
or_hline(int x1, int x2, int y, unsigned color)
{
	int x;
	for (x = x1; x < x2; x++) {
		jint *p = ADDR_PIXEL(x, y);
		*p |= color;
	}
}

static void
or_vline(int x, int y1, int y2, unsigned color)
{
	int y;
	for (y = y1; y < y2; y++) {
		jint *p = ADDR_PIXEL(x, y);
		*p |= color;
	}
}



#define SEVEN_CHARS 40
#define SEVEN_WID (width/SEVEN_CHARS)
/* seven segment display:
 *      0x01
 *  0x02    0x04
 *      0x08
 *  0x10    0x20
 *      0x40
 */
static unsigned
char_to_bits(char c)
{
	switch (c) {
		case '0': return 0x77;
		case '1': return 0x24;
		case '2': return 0x5D;
		case '3': return 0x6D;
		case '4': return 0x2E;
		case '5': return 0x6B;
		case '6': return 0x7B;
		case '7': return 0x25;
		case '8': return 0x7F;
		case '9': return 0x6F;
		case 'A': return 0x3F;
		case 'B': return 0x7A;
		case 'C': return 0x53;
		case 'D': return 0x7C;
		case 'E': return 0x5B;
		case 'F': return 0x1B;
		case 'G': return 0x73;
	}
	return 0x28;
}

static void
seven_seg(int x, int y, char c, unsigned color)
{
	unsigned bits = char_to_bits(c);
	int l1,l2;
	int x1,x2,x3,x4;
	int y1,y2,y3,y4,y5,y6;

	l1 = SEVEN_WID/5;
	if (l1 < 1) l1 = 1;
	l2 = SEVEN_WID-(3*l1);
	if (l2 < 1) l2 = 1;

	x1 = x;
	x2 = x1+l1;
	x3 = x2+l2;
	x4 = x3+l1;
	if ((x1 < 0) || (x4 > width)) return;

	y1 = y;
	y2 = y1+l1;
	y3 = y2+l2;
	y4 = y3+l1;
	y5 = y4+l2;
	y6 = y5+l1;
	if ((y1 < 0) || (y6 > height)) return;

	if (bits & 0x01) filled_rect(x2,y1,x3,y2, color);
	if (bits & 0x02) filled_rect(x1,y2,x2,y3, color);
	if (bits & 0x04) filled_rect(x3,y2,x4,y3, color);
	if (bits & 0x08) filled_rect(x2,y3,x3,y4, color);
	if (bits & 0x10) filled_rect(x1,y4,x2,y5, color);
	if (bits & 0x20) filled_rect(x3,y4,x4,y5, color);
	if (bits & 0x40) filled_rect(x2,y5,x3,y6, color);
}

#define NUM_CLEAR 25	/* number of columns to clear at once */
static void
clear_col(struct strobe_rec *s, int col)
{
	int start_x, stop_x;
	if (col < s->last_cleared_col) {
		if (col+(2*NUM_CLEAR) < s->last_cleared_col) {
			/* must have looped to zero */
			col += width;
		} else {
			/* nothing to do, already cleared */
			return;
		}
	} else if (col+(2*NUM_CLEAR) >= s->last_cleared_col+width) {
		/* this column has already been cleared, just last_cleared_col
		 * has looped to zero */
		return;
	}
		
	start_x = s->last_cleared_col;
	stop_x = col+NUM_CLEAR;
	s->last_cleared_col = (stop_x % width);

	if (stop_x > width) {
		if (stop_x >= width*2) {
			start_x = 0;
			stop_x = 0;
		}
		filled_rect(start_x, s->y, width, s->y+s->height, 0x000000);
		start_x = 0;
		stop_x -= width;
	}

	if (start_x == stop_x) {
		return;
	}
	filled_rect(start_x, s->y, stop_x+1, s->y+s->height, 0x000000);
}

static void
strobe_drawnew(struct strobe_rec *s)
{
	static int exptab[256] = { -1, };
	unsigned pos = s->prev_pos;
	unsigned pos_width = s->msamps_per_col*width;
	unsigned color, last_color = -1;
	int samp_max = s->samp_max;
	int samp_divider = 1+(samp_max/256);
	int last_x = -1, last_y = -1;
	int x,y;
	int i;
	int multiplier = s->multiplier;

	if (exptab[0] == -1) {
#define LOG_STEEP 40.0
		int i;
		double scale = 254.0/log(LOG_STEEP*256.0);
		for (i = 0; i < 256; i++) {
			exptab[i] = log((i+1.0)*LOG_STEEP)*scale;
		}
	}

	i = s->curr_samp % SND_BUFSAMPS;
	while (i != snd_ofs) {
		int samp = s->filtered[i];

		if (abs(samp) > samp_max) {
			samp_max = abs(samp);
			samp_divider = 1+(samp_max/256);
		}
		samp /= samp_divider;

		pos += 1000;
		pos %= (pos_width * multiplier);
		x = pos / (s->msamps_per_col * multiplier);
		y = ((pos % s->msamps_per_col) * s->height) / s->msamps_per_col;
		if (x != last_x) {
			clear_col(s, x);
			last_x = x;
		} else if (y > last_y+1) {
			/* if there are more pixels than samples, then fill in
			 * the in-betweens */
			int ty;
			for (ty = last_y+1; ty < y; ty++) {
				DRAW_PIXEL(last_x, ty+s->y, last_color);
			}
		}

		if (samp >= 0) {
			int e = exptab[samp];
			color = COLOR_VALUE(e, e/2, e/2);
		} else {
			int e = exptab[-samp];
			color = COLOR_VALUE(e/2, e, e/2);
		}
		DRAW_PIXEL(x, y+s->y, color);

		last_y = y;
		last_color = color;

		s->curr_samp++;
		i = s->curr_samp%SND_BUFSAMPS;
	}
	s->prev_pos = pos;
	s->samp_max = samp_max;
}

/* draw a horizontal tic mark at 1 second into the buffer */
static void
strobe_draw_htic(struct strobe_rec *s)
{
	int x, y;
	int step;
	step = s->freq / s->multiplier;
	for (x = step; x < width; x += step) {
		for (y = (s->height*8)/10; y < s->height; y++) {
			DRAW_PIXEL(x, y+s->y, COLOR_VALUE(0xff,0xff,0xaa));
		}
	}
}

static void
strobe_newdata(struct strobe_rec *s)
{
	if (s->curr_samp == snd_ofs) {
		/* not really any new data */
		return;
	}
	strobe_filter(s);
	strobe_drawnew(s);
	strobe_draw_htic(s);
}


/* just precompute the needed cos/sin values for FFT */
static void
init_fft(void)
{
	int l, le, le2;
	for (l = 1; l < (sizeof fft_sr/sizeof fft_sr[0]); l++) {
		le = 1<<l;
		le2 = le>>1;
		fft_sr[l] = cos(M_PI/((double)le2));
		fft_si[l] = -sin(M_PI/((double)le2));
	}
}

static unsigned
lg2(int x)
{
	unsigned ret = 0;
	if (x == 0) {
		return 0;
	}
	while (x != 1) {
		x >>= 1;
		ret++;
	}
	return ret;
}

/* this is Fast Fourier Transform, cribbed from The Scientist and Engineer's
 * Guide to Digital Signal Processing by Steven W. Smith.
 * O(n*lg(n))
 * in: fft_r[0..n-1]
 * out: fft_r/i[0..n/2] */
static void
do_fft(void)
{
	int i,j,k,l,m;
	int le,le2,ip;
	double ur,ui,sr,si,tr,ti;

	m = lg2(fft_size);
	j = fft_size/2;

	memset(fft_i, 0, sizeof fft_i);
	for (i = 1; i <= fft_size-2; i++) {
		if (i < j) {
			double t;
			t = fft_r[j];
			fft_r[j] = fft_r[i];
			fft_r[i] = t;
		}
		k = fft_size/2;
		while (k <= j) {
			j -= k;
			k /= 2;
		}
		j += k;
	}

	for (l = 1; l <= m; l++) {
		le = 1<<l;
		le2 = le>>1;
		ur = 1;
		ui = 0;
		sr = fft_sr[l];
		si = fft_si[l];
		for (j = 1; j <= le2; j++) {
			for (i = j-1; i <= fft_size-1; i += le) {
				ip = i+le2;
				tr = fft_r[ip]*ur - fft_i[ip]*ui;
				ti = fft_r[ip]*ui + fft_i[ip]*ur;
				fft_r[ip] = fft_r[i]-tr;
				fft_i[ip] = fft_i[i]-ti;
				fft_r[i] += tr;
				fft_i[i] += ti;
			}
			tr = ur;
			ur = tr*sr - ui*si;
			ui = tr*si + ui*sr;
		}
	}
}

static int
fft_freq2ind(double freq)
{
	if ((int)freq >= sample_rate/2) {
		return fft_size/2;
	}
	return ((double)fft_size)*(freq/((double)sample_rate));
}

static void
fft_draw_slider(void)
{
	int x1 = (fft_offset*width) / FFT_RANGE_MULT;
	int x2 = ((fft_offset+fft_width)*width)/FFT_RANGE_MULT;
	int y1 = height-(fft_legend_height/3);

	if (x1 < 0) x1 = 0;
	if (x1 >= width) x1 = width-1;
	if (x2 < 0) x2 = 0;
	if (x2 > width) x2 = width;
	if (x1 > 0) {
		filled_rect(0,y1,x1,height, 0x00FF0000);
	}
	filled_rect(x1,y1,x2,height, 0x0000FF00);
	if (x2 < width) {
		filled_rect(x2,y1,width,height,0x00FF0000);
	}
}

static int grid_x[SEVEN_CHARS];
static int num_grid_x = 0;

#define COMPUTE_BINS \
	tot_bins = fft_size/2; \
	first_bin = ((fft_offset*tot_bins)/FFT_RANGE_MULT); \
	num_bins = ((fft_width*tot_bins)/FFT_RANGE_MULT); \
	if (first_bin < 0) first_bin = 0; \
	if (num_bins > tot_bins) num_bins = tot_bins; \
	if (first_bin + num_bins > tot_bins) first_bin = tot_bins - num_bins;

static void
fft_draw_piano(jobject this)
{
	jobject ui = (*env)->GetObjectField(env, this, fid_st_ui);
	int y1,y2,y3;
	int tot_bins, first_bin, num_bins;
	int last_x = 0;
	int i;

	y1 = height - fft_legend_height;
	y3 = height - (fft_legend_height/3);
	y2 = y3 - (fft_legend_height/4);

	COMPUTE_BINS

	num_grid_x = 0;
	for (i = 0; i < 88; i++) {
		double freq, bin;
		int x, x0, x1, x2;
		static const char *(names[12]) = { "A", NULL, "B", "C", NULL, "D", NULL, "E", "F", NULL, "G", NULL };
		const char *name = names[i % 12];

		freq = (*env)->CallDoubleMethod(env, ui, mid_tt_cents_to_freq, (jint)i*100, (jint)1);

		bin = ((double)fft_size)*(freq/((double)sample_rate));
		if ((int)bin < first_bin) continue;
		if ((int)bin >= first_bin+num_bins) break;

		x = (int)(((bin-(double)first_bin)*(double)width) / (double)num_bins);
		x0 = x - (SEVEN_WID-1);
		x1 = x - 1;
		x2 = x + 2;
		if (x1 < 0) x1 = 0;
		if (x2 > width) x2 = width;

		if (name) {			/* white keys */
			filled_rect(x1, y2, x2, y3, 0x00FFFFFF);
			if (x0 >= last_x) {
				seven_seg(x0, y1, name[0], 0x00AAAAFF);
				seven_seg(x0+SEVEN_WID, y1, (i/12)+'0', 0x00AAAAFF);
				last_x = x0 + (SEVEN_WID*2);
				or_vline(x, 0, y1, 0x00444411);
				grid_x[num_grid_x++] = x;
			}
		} else {
			filled_rect(x1, y2, x2, y3, 0x00666666);
		}
	}

	(*env)->DeleteLocalRef(env, ui);
}

static int
round_up(int i)
{
	int mult = 1;
	while (i > 99) {
		mult *= 10;
		i /= 10;
	}
	if (i >= 50) {
		return 100*mult;
	}
	if ( i >= 25) {
		return 50*mult;
	}
	return 25*mult;
}

static void
fft_draw_freqs(void)
{
	int tot_bins, first_bin, num_bins;
	int y1,y2,y3;
	int freq_lo, freq_range;
	int f, step;

	y1 = height - fft_legend_height;
	y3 = height - (fft_legend_height/3);
	y2 = y3 - (fft_legend_height/4);

	COMPUTE_BINS

	freq_lo = (first_bin * sample_rate) / fft_size;
	freq_range = (num_bins * sample_rate) / fft_size;

	step = freq_range / 7;
	step = round_up(step);

	num_grid_x = 0;
	for (f = (freq_lo/step)*step; ; f += step) {
		double bin;
		int x, x0, x1, x2, x3;
		int w;
		int i;
		char label[20];

		sprintf(label, "%d", f);

		bin = ((double)fft_size)*((double)f/((double)sample_rate));
		if ((int)bin < first_bin) continue;
		if ((int)bin >= first_bin+num_bins) break;
		x = (int)(((bin-(double)first_bin)*(double)width) / (double)num_bins);

		w = SEVEN_WID*strlen(label);
		x0 = x - (w / 2);
		x1 = x - 1;
		x2 = x + 2;
		x3 = x0 + w;
		if (x1 < 0) x1 = 0;
		if (x2 > width) x2 = width;

		filled_rect(x1, y2, x2, y3, 0x00FFFFFF);
		or_vline(x, 0, y1, 0x00444411);
		grid_x[num_grid_x++] = x;
		if ((x0 >= 0) && (x3 < width)) {
			for (i = 0; label[i]; i++) {
				seven_seg(x0 + (SEVEN_WID*i), y1, label[i],
							0x00AAAAFF);
			}
		}
	}
}

static void
fft_draw_seconds(void)
{
	int y, y1;

	y1 = height - fft_legend_height;

	for (y = (y1/FFT_SECONDS); y < (y1-(FFT_SECONDS*2));
	     y += (y1/FFT_SECONDS)) {
		or_hline(0, width, y, 0x444411);
	}
}

static void
do_fft_display(void)
{
	int tot_bins, first_bin, num_bins;
	int i;
	int y1, y2, xn;
	int incr;
	double avg = 0.;
	double scale;
	int disp_height = height-fft_legend_height;

	y1 = fft_y;
	y2 = y1 + ((disp_height*fft_size) / (FFT_SECONDS*sample_rate));
	if (y2 > disp_height) y2 = disp_height;

	COMPUTE_BINS

	incr = num_bins / width;
	if (incr < 1) incr = 1;

	xn = (width+(num_bins-1))/num_bins;
	if (xn < 1) xn = 1;

		/* normalize the data, and compute the average */
	for (i = 0; i < num_bins; i++) {
		int n = i+first_bin;
		double t = fabs(fft_r[n]) + fabs(fft_i[n]);
		fft_r[n] = t;
		avg += t;
	}
	avg /= (((double)num_bins) / ((double)incr));
	scale = 32./avg;

		/* draw fft data */
	for (i = 0; i < num_bins; i+=incr) {
		int x1 = (i*width)/num_bins;
		double val = 0;
		unsigned color;
		int j;
		for (j = 0; j < incr; j++) {
			val += fft_r[i+j+first_bin];
		}
		color = val * scale;
		if (color > 255) color = 255;
		color = COLOR_VALUE(0,color,color/2);
		filled_rect(x1, y1, x1+xn, y2, color);
	}

		/* draw grid */
	for (i = 0; i < num_grid_x; i++) {
		or_vline(grid_x[i], y1, y2, 0x444411);
	}
	for (i = (disp_height/FFT_SECONDS); i < (disp_height-(FFT_SECONDS*2));
	     i += (disp_height/FFT_SECONDS)) {
		if ((y1 <= i) && (y2 > i)) {
			or_hline(0, width, i, 0x444411);
		}
	}

	fft_y = y2;
	if (fft_y >= disp_height) {
		fft_y = 0;
	}
}

void
newdata(void)
{
	int i;
	if (fft_mode) {
		for (i = nf_curr_samp; i != snd_ofs; i = (i+1)%SND_BUFSAMPS) {
			fft_r[nf_curr_ridx++] = ((double)snd_buf[i]) / 32768.0;
			if (nf_curr_ridx >= fft_size) {
				do_fft();
				do_fft_display();
				nf_curr_ridx = 0;
			}
		}
	nf_curr_samp = i;
	} else {
		for (i = 0; i < NUM_STROBES; i++) {
			strobe_newdata(&strobes[i]);
		}
	}
}



/* sets up the tokens needed to reference Java things */
static int
jni_init(JNIEnv *env_)
{
	env = env_;
#define CLASS(var, id) \
	cl_##var = (*env)->FindClass(env, id); \
	if (!cl_##var) return 0;
#define METHOD(var, mycl, id, sig) \
	mid_##var = (*env)->GetMethodID(env, cl_##mycl, id, sig); \
	if (!mid_##var) return 0;
#define FIELD(var, mycl, id, sig) \
	fid_##var = (*env)->GetFieldID(env, cl_##mycl, id, sig); \
	if (!fid_##var) return 0;
#define STFIELD(var, mycl, id, sig) \
	fid_##var = (*env)->GetStaticFieldID(env, cl_##mycl, id, sig); \
	if (!fid_##var) return 0;


	CLASS(system, "java/lang/System")
	CLASS(printstream, "java/io/PrintStream")
	CLASS(string, "java/lang/String")
	CLASS(strobe, "org/galexander/tunertime/Strobe")
	CLASS(tunertime, "org/galexander/tunertime/TunerTime")
	CLASS(audiorecord, "android/media/AudioRecord")

	METHOD(println, printstream, "println", "(Ljava/lang/String;)V")
	METHOD(st_do_draw, strobe, "do_draw", "([III)Z")
	METHOD(st_time_to_die, strobe, "time_to_die", "()Z")
	METHOD(tt_cents_to_freq, tunertime, "cents_to_freq", "(II)D")
	METHOD(ar_getsamplerate, audiorecord, "getSampleRate", "()I")
	METHOD(ar_read, audiorecord, "read", "([SII)I")

	STFIELD(out, system, "out", "Ljava/io/PrintStream;")
	FIELD(st_cents, strobe, "cents", "I")
	FIELD(st_harm1_mult, strobe, "harm1_mult", "I")
	FIELD(st_harm2_mult, strobe, "harm2_mult", "I")
	FIELD(st_cents_changed, strobe, "cents_changed", "Z")
	FIELD(st_width, strobe, "width", "I")
	FIELD(st_height, strobe, "height", "I")
	FIELD(st_surface_changed, strobe, "surface_changed", "Z")
	FIELD(st_recorder, strobe, "recorder", "Landroid/media/AudioRecord;")
	FIELD(st_record_bufsz, strobe, "record_bufsz", "I")
	FIELD(st_labels, strobe, "labels", "[Ljava/lang/String;")
	FIELD(st_ui, strobe, "ui", "Lorg/galexander/tunertime/TunerTime;")
	FIELD(st_fft_changed, strobe, "fft_changed", "Z")
	FIELD(st_fft_mode, strobe, "fft_mode", "Z")
	FIELD(st_fft_size, strobe, "fft_size", "I")
	FIELD(st_fft_width, strobe, "fft_width", "I")
	FIELD(st_fft_offset, strobe, "fft_offset", "I")
	FIELD(st_fft_grid_freq, strobe, "fft_grid_freq", "Z")
	FIELD(st_paused, strobe, "paused", "Z");

	return 1;
}

static void
println(const char *fmt, ...)
{
	char buf[1000];
	va_list ap;
	va_start(ap, fmt);
	vsprintf(buf,fmt,ap);
	va_end(ap);
	jstring *x = (*env)->NewStringUTF(env, buf);
	if (!x) return;
	jobject out = (*env)->GetStaticObjectField(env, cl_system, fid_out);
	if (!out) return;
	(*env)->CallVoidMethod(env, out, mid_println, x);
	(*env)->DeleteLocalRef(env, out);
	(*env)->DeleteLocalRef(env, x);
}


static void
resize_bitmap(void)
{
	if (bitmap) {
		(*env)->DeleteLocalRef(env, bitmap);
	}
	bitmap = (*env)->NewIntArray(env, width*height);
}

static void
recalculate(jobject this)
{
	int i;
	int cents = base_cents;
	int sheight = height/NUM_STROBES;
	int y = 0;
	jobject ui;

	ui = (*env)->GetObjectField(env, this, fid_st_ui);

	for (i = 0; i < NUM_STROBES; i++) {
		int mult = ((i==0) ? 1 : ((i==1) ? harm1_mult : harm2_mult));
		strobes[i].y = y;
		strobes[i].height = sheight;
		strobes[i].freq = (*env)->CallDoubleMethod(env, ui, mid_tt_cents_to_freq, (jint)cents, (jint)mult);
		strobe_recalc(&strobes[i]);

		y += sheight;
	}

	(*env)->DeleteLocalRef(env, ui);
}

static void
update_labels(jobject this)
{
	jobjectArray labels = (*env)->NewObjectArray(env, NUM_STROBES, cl_string, NULL);
	int i;
	for (i = 0; i < NUM_STROBES; i++) {
		jstring lab;
		char buf[100];
		int mult = ((i==0) ? 1 : ((i==1) ? harm1_mult : harm2_mult));
		char *ext = ((mult==1) ? "st" : ((mult==2) ? "nd" :
				((mult==3) ? "rd" : "th")));
		sprintf(buf, "%2d%s %7.1f", mult, ext, strobes[i].freq);
		lab = (*env)->NewStringUTF(env, buf);
		(*env)->SetObjectArrayElement(env, labels, i, lab);
		(*env)->DeleteLocalRef(env, lab);
	}
	(*env)->SetObjectField(env, this, fid_st_labels, labels);
	(*env)->DeleteLocalRef(env, labels);
}

static void
fft_mode_changed(jobject this)
{
	memset(bitmap_ptr, 0, width*height*sizeof *bitmap_ptr);
	if (fft_mode) {
		fft_y = 0;
		fft_legend_height = height/10;
		fft_draw_slider();
		if (fft_grid_freq) {
			fft_draw_freqs();
		} else {
			fft_draw_piano(this);
		}
		fft_draw_seconds();
	} else {
		int i;
		for (i = 0; i < NUM_STROBES; i++) {
			strobes[i].prev_pos = 0;
		}
	}
}

static void
check_fft_mode(jobject this)
{
	if (!(*env)->GetBooleanField(env, this, fid_st_fft_changed)) {
		return;
	}

	(*env)->SetBooleanField(env, this, fid_st_fft_changed, JNI_FALSE);
	fft_mode = (*env)->GetBooleanField(env, this, fid_st_fft_mode);
	fft_size = (*env)->GetIntField(env, this, fid_st_fft_size);
	fft_width = (*env)->GetIntField(env, this, fid_st_fft_width);
	fft_offset = (*env)->GetIntField(env, this, fid_st_fft_offset);
	fft_grid_freq = (*env)->GetIntField(env, this, fid_st_fft_grid_freq);
	fft_mode_changed(this);
}

/* This is the entry point from the Java code...it is a loop that checks for
 * thread state changes, reads audio, then writes to screen. */
JNIEXPORT void JNICALL
Java_org_galexander_tunertime_Strobe_runner(JNIEnv *env_, jobject this)
{
	jshortArray readbuf;
	jobject recorder;
	jint record_bufsz;

	if (!jni_init(env_)) {
		return;
	}

	memset(strobes, 0, sizeof strobes);
	bitmap = NULL;

	init_fft();
	
	recorder = (*env)->GetObjectField(env, this, fid_st_recorder);
	record_bufsz = (*env)->GetIntField(env, this, fid_st_record_bufsz);
	sample_rate = (*env)->CallIntMethod(env, recorder, mid_ar_getsamplerate);
	record_bufsz /= sizeof(snd_buf[0]);	/* was bytes */

	readbuf = (*env)->NewShortArray(env, record_bufsz);

	while (1) {
		int num_read, to_read;

		/* first, check for any important state changes */
		if ((*env)->GetBooleanField(env, this, fid_st_cents_changed)) {
			(*env)->SetBooleanField(env, this, fid_st_cents_changed, JNI_FALSE);
			base_cents = (*env)->GetIntField(env, this, fid_st_cents);
			harm1_mult = (*env)->GetIntField(env, this, fid_st_harm1_mult);
			harm2_mult = (*env)->GetIntField(env, this, fid_st_harm2_mult);
			recalculate(this);
			update_labels(this);
		}
		if ((*env)->GetBooleanField(env, this, fid_st_surface_changed)) {
			(*env)->SetBooleanField(env, this, fid_st_surface_changed, JNI_FALSE);
			width = (*env)->GetIntField(env, this, fid_st_width);
			height = (*env)->GetIntField(env, this, fid_st_height);
			recalculate(this);
			resize_bitmap();
		}
		if ((*env)->CallBooleanMethod(env, this, mid_st_time_to_die)) {
			/* thread was interrupted, must be time for it to die */
			println("thread interrupted");
			return;
		}

		/* then get sound */
		to_read = SND_BUFSAMPS-snd_ofs;
		if (to_read > record_bufsz) {
			to_read = record_bufsz;
		}
		num_read = (*env)->CallIntMethod(env, recorder, mid_ar_read, readbuf, 0, to_read);
		if (num_read <= 0) {
			return;
		}
		(*env)->GetShortArrayRegion(env, readbuf, 0, num_read, snd_buf+snd_ofs);
		snd_ofs = (snd_ofs+num_read)%SND_BUFSAMPS;

		if (bitmap) {
			/* process sound into bitmap */
			bitmap_ptr = (*env)->GetPrimitiveArrayCritical(env, bitmap, 0);

			check_fft_mode(this);

			if (!(*env)->GetBooleanField(env,this,fid_st_paused)) {
				newdata();
			}
			(*env)->ReleasePrimitiveArrayCritical(env, bitmap, bitmap_ptr, 0);
			/* send bitmap to surface */
			if ((*env)->CallBooleanMethod(env, this, mid_st_do_draw, bitmap, width, height)) {
				/* thread was interrupted, must be time for it to die */
				println("thread interrupted in do_draw()");
				return;
			}
		}
	}
}
