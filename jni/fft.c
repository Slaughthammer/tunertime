#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <math.h>
#include <assert.h>
#include "interface.h"
#include "window.h"

int fft_mode = 0;
int fft_size = 8192;
int fft_height = FFT_RANGE_MULT;		/* size of freq window we are viewing */
int fft_offset = 0;			/* offset, out of FFT_RANGE_MULT */
int fft_grid_freq = 0;

static double fft_buf[MAX_FFT_SIZE];
static int fft_buf_i = 0;			/* output */
static double fft_sr[32];
static double fft_si[32];
static struct window_rec *curr_window = NULL;

static int fft_legend_width = 0;


/* allocate enough space for about 60 seconds */
/* note that head/tail are in units of fft_size/2 (the spectrum is half the size
 * of fft_size, because we discard the phase information) */
#define FFT_HIST_BUFSZ (44100*60)
static double *fft_hist_buf;
static int fft_hist_head = 0, fft_hist_tail = 0;
static int fft_hist_count = 0;		/* BUFSZ / (fft_size/2) */

static double *
fft_hist_next(void)
{
	fft_hist_tail = (fft_hist_tail+1) % fft_hist_count;

	if (fft_hist_tail == fft_hist_head) {
		/* running into back of head */
		fft_hist_head = ((fft_hist_head + 1) % fft_hist_count);
	}
	return &fft_hist_buf[fft_hist_tail*fft_size/2];
}

/* ofs is negative frames counting backwards from tail */
static double *
fft_hist_get(int ofs)
{
	int avail = ((fft_hist_tail > fft_hist_head)
			? (fft_hist_tail - fft_hist_head)
			: (fft_hist_tail + fft_hist_count - fft_hist_head));
	if (avail < ofs) {
		return NULL;
	}
	return &fft_hist_buf[((fft_hist_tail + fft_hist_count - ofs) %
			      fft_hist_count) * fft_size/2];
}


/* just precompute the needed cos/sin values for FFT */
void
fft_init(void)
{
	int l, le, le2;
	for (l = 1; l < (sizeof fft_sr/sizeof fft_sr[0]); l++) {
		le = 1<<l;
		le2 = le>>1;
		fft_sr[l] = cos(M_PI/((double)le2));
		fft_si[l] = -sin(M_PI/((double)le2));
	}
	if (!fft_hist_buf) {
		fft_hist_buf = malloc(FFT_HIST_BUFSZ*sizeof *fft_hist_buf);
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
 * in: fft_in[0..n-1]
 * computes: fft_r/i[0..n/2]  (imaginary half provides phase information)
 * out: fft_out[0..n/2]
 * NOTE: overwrites fft_in, which it uses internally as fft_r. */
static void
do_fft(double *fft_in, double *fft_out)
{
	static double fft_i[MAX_FFT_SIZE];
	double *fft_r = fft_in;
	int i,j,k,l;
	int m = lg2(fft_size);

	memset(fft_i, 0, sizeof fft_i);
	j = fft_size/2;
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
		int le = 1<<l, le2 = le>>1;
		double ur = 1, ui = 0;
		double sr = fft_sr[l], si = fft_si[l];
		for (j = 1; j <= le2; j++) {
			for (i = j-1; i <= fft_size-1; i += le) {
				int ip = i+le2;
				double tr = fft_r[ip]*ur - fft_i[ip]*ui;
				double ti = fft_r[ip]*ui + fft_i[ip]*ur;
				fft_r[ip] = fft_r[i]-tr;
				fft_i[ip] = fft_i[i]-ti;
				fft_r[i] += tr;
				fft_i[i] += ti;
			}
			double t = ur;
			ur = t*sr - ui*si;
			ui = t*si + ui*sr;
		}
	}

		/* convert back to real-value (discard phase information) */
	for (i = 0; i < fft_size/2; i++) {
		double re = fft_r[i], im = fft_i[i];
		fft_out[i] = sqrt(re*re+im*im);
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
fft_draw_slider(jint *bitmap_ptr)
{
	int y1 = (fft_offset*height) / FFT_RANGE_MULT;
	int y2 = ((fft_offset+fft_height)*height)/FFT_RANGE_MULT;
	int x1 = width-(fft_legend_width/3);

	if (y1 < 0) y1 = 0;
	if (y1 >= height) y1 = height-1;
	if (y2 < 0) y2 = 0;
	if (y2 > height) y2 = height;
	if (y1 > 0) {
		filled_rect(bitmap_ptr, x1,0,width,y1, 0xFFFF0000);
	}
	filled_rect(bitmap_ptr, x1,y1,width,y2, 0xFF00FF00);
	if (y2 < height) {
		filled_rect(bitmap_ptr, x1,y2,width,height, 0xFFFF0000);
	}
}


static double piano_freqs[88];

void
fft_recalculate(jobject this)
{
	int i;
	for (i = 0; i < 88; i++) {
		piano_freqs[i] = cents_to_freq(this, i*100, 1);
	}
	fft_hist_count = FFT_HIST_BUFSZ / (fft_size/2);
}

static int grid_y[SEVEN_CHARS];
static int num_grid_y = 0;

#define COMPUTE_BINS \
	tot_bins = fft_size/2; \
	first_bin = ((fft_offset*tot_bins)/FFT_RANGE_MULT); \
	num_bins = ((fft_height*tot_bins)/FFT_RANGE_MULT); \
	if (first_bin < 0) first_bin = 0; \
	if (num_bins > tot_bins) num_bins = tot_bins; \
	if (first_bin + num_bins > tot_bins) first_bin = tot_bins - num_bins;

static void
fft_draw_piano(jint *bitmap_ptr)
{
	int x1,x2,x3;
	int tot_bins, first_bin, num_bins;
	int last_y = 0;
	int i;

	x1 = width - fft_legend_width;
	x3 = width - (fft_legend_width/3);
	x2 = x3 - (fft_legend_width/4);

	COMPUTE_BINS

	num_grid_y = 0;
	for (i = 0; i < 88; i++) {
		double freq, bin;
		int y, y0, y1, y2;
		static const char *(names[12]) = { "C", NULL, "D", NULL, "E", "F", NULL, "G", NULL, "A", NULL, "B" };
		const char *name = names[(i+9) % 12];

		freq = piano_freqs[i];

		bin = ((double)fft_size)*(freq/((double)sample_rate));
		if ((int)bin < first_bin) continue;
		if ((int)bin >= first_bin+num_bins) break;

		y = (int)(((bin-(double)first_bin)*(double)height) / (double)num_bins);
		y0 = y - (SEVEN_WID-1);
		y1 = y - 1;
		y2 = y + 2;
		if (y1 < 0) y1 = 0;
		if (y2 > height) y2 = height;

		if (name) {			/* white keys */
			filled_rect(bitmap_ptr, x2, y1, x3, y2, 0xFFFFFFFF);
			if (y0 >= last_y) {
				seven_seg(bitmap_ptr, x1, y0,
					name[0], 0xFFAAAAFF);
				seven_seg(bitmap_ptr, x1+SEVEN_WID, y0,
					((i+9)/12)+'0', 0xFFAAAAFF);
				last_y = y0 + (SEVEN_WID*2);
				xor_hline(bitmap_ptr, 0, x1, y);
				grid_y[num_grid_y++] = y;
			}
		} else {
			filled_rect(bitmap_ptr, x2, y1, x3, y2, 0xFF666666);
		}
	}
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
fft_draw_freqs(jint *bitmap_ptr)
{
	int tot_bins, first_bin, num_bins;
	int x1,x2,x3;
	int freq_lo, freq_range;
	int f, step;

	x1 = width - fft_legend_width;
	x3 = width - (fft_legend_width/3);
	x2 = x3 - (fft_legend_width/4);

	COMPUTE_BINS

	freq_lo = (first_bin * sample_rate) / fft_size;
	freq_range = (num_bins * sample_rate) / fft_size;

	step = freq_range / 7;
	step = round_up(step);

	num_grid_y = 0;
	for (f = (freq_lo/step)*step; ; f += step) {
		double bin;
		int y, y0, y1, y2, y3;
		int w;
		int i;
		char label[20];

		sprintf(label, "%d", f);

		bin = ((double)fft_size)*((double)f/((double)sample_rate));
		if ((int)bin < first_bin) continue;
		if ((int)bin >= first_bin+num_bins) break;
		y = (int)(((bin-(double)first_bin)*(double)height) / (double)num_bins);

		w = SEVEN_WID*strlen(label);
		y0 = y - (w / 2);
		y1 = y - 1;
		y2 = y + 2;
		y3 = y0 + w;
		if (y1 < 0) y1 = 0;
		if (y2 > height) y2 = height;

		filled_rect(bitmap_ptr, x2, y1, x3, y2, 0xFFFFFFFF);
		xor_hline(bitmap_ptr, 0, x1, y);
		grid_y[num_grid_y++] = y;
		if ((y0 >= 0) && (y3 < height)) {
			for (i = 0; label[i]; i++) {
				seven_seg(bitmap_ptr,
					x1 + (SEVEN_WID*i), y0, label[i],
					0xFFAAAAFF);
			}
		}
	}
}

static void
fft_draw_seconds(jint *bitmap_ptr)
{
	int x, x1;

	x1 = width - fft_legend_width;

	for (x = (x1/FFT_SECONDS); x < (x1-(FFT_SECONDS*2));
	     x += (x1/FFT_SECONDS)) {
		xor_vline(bitmap_ptr, x, 0, height);
	}
}

/* x should be [0, 1.0] */
static unsigned
color_for_value(double x)
{
#define NUM_RANGES 5
	static const unsigned spectrum[NUM_RANGES+1][3] =
			{ { 0, 0, 0 },		/* black */
			  { 1, 0, 1 },		/* purple */
			  { 0, 0, 1 },		/* blue */
			  { 0, 1, 0 },		/* green */
			  { 1, 1, 0 },		/* orange */
			  { 1, 0, 0 } };	/* red */
	int range = 0;
	unsigned r1, g1, b1;
	unsigned r2, g2, b2;
	unsigned r, g, b;
	unsigned ix, i1mx;

	if (x < 0.0000001) {
		return 0xFF000000;
	}
	x = log(1.0 + x * 500.0) / 10.3;
	if (x < 0.0) {
		return 0xFF000000;
	}
	if (x > 1.0) {
		x = 1.0;
	}

	x *= ((double)NUM_RANGES);
	while (x > 1.0) {
		x -= 1.0;
		range++;
	}
	if (range > NUM_RANGES-1) {
		range = NUM_RANGES-1;
		x = 1.0;
	}
	r1 = spectrum[range][0];
	g1 = spectrum[range][1];
	b1 = spectrum[range][2];
	r2 = spectrum[range+1][0];
	g2 = spectrum[range+1][1];
	b2 = spectrum[range+1][2];
	ix = x*255.0;
	i1mx = 255-ix;
	r = i1mx*r1 + ix*r2;
	g = i1mx*g1 + ix*g2;
	b = i1mx*b1 + ix*b2;
	return COLOR_VALUE(r,g,b);
}


//XXX - first, rotate it
//XXX - then, decide book-keeping for describing time offset and scale and paused in the java ui (if it's scrolled back, tapping will unpause it and bring it to real time)
//XXX - then honor the time offset back here, which should be the super easy part

static void
fft_display_col(jint *bitmap_ptr, int x1, int x2, double *fft_data)
{
	int tot_bins, first_bin, num_bins;
	int i;
	int yn;
	int incr;
	double dincr;

	COMPUTE_BINS

	if (!height || !num_bins || !fft_data) {	/* prevent divide by zero */
		filled_rect(bitmap_ptr, x1, 0, x2, height, 0xFF222222);
		return;
	}

	incr = num_bins / height;
	if (incr < 1) incr = 1;

	yn = (height+(num_bins-1))/num_bins;
	if (yn < 1) yn = 1;

	dincr = incr;
		/* draw fft data */
	for (i = 0; i < num_bins; i+=incr) {
		int y1;
		double val = 0;
		unsigned color;
		int j;
		for (j = 0; j < incr; j++) {
			val += fft_data[i+j+first_bin];
		}
		val /= dincr;
		color = color_for_value(val);
		y1 = (i*height)/num_bins;
		filled_rect(bitmap_ptr, x1, y1, x2, y1+yn, color);
	}

//XXX this can be factored out now and done uniformly after the cols
		/* draw grid */
	for (i = 0; i < num_grid_y; i++) {
		xor_hline(bitmap_ptr, x1, x2, grid_y[i]);
	}
	int disp_width = width-fft_legend_width;
	for (i = (disp_width/FFT_SECONDS); i < (disp_width-(FFT_SECONDS*2));
	     i += (disp_width/FFT_SECONDS)) {
		if ((x1 <= i) && (x2 > i)) {
			xor_vline(bitmap_ptr, i, 0, height);
		}
	}
}

static void
fft_display(jint *bitmap_ptr)
{
	int disp_width = width-fft_legend_width;
	int cols = ((disp_width*fft_size) / (2*FFT_SECONDS*sample_rate));
	if (cols < 1) {
		cols = 1;
	}
	int x2 = disp_width;
	int x1 = x2-cols;
	int i;
	for (i = 0; ; i++) {
		fft_display_col(bitmap_ptr, x1, x2, fft_hist_get(i));
		if (!x1) break;		/* done with leftmost column */
		x2 = x1;
		x1 = ((x1 > cols) ? (x1 - cols) : 0);
	}
}


void
fft_newdata(jint *bitmap_ptr)
{
	int i;
	int did_any = 0;

	for (i = 0; i < snd_buflen; i++) {
		fft_buf[fft_buf_i++] = ((double)snd_buf[i]) / 32768.0;
		if (fft_buf_i >= fft_size) {
			double fft_in[MAX_FFT_SIZE];
			window_apply(curr_window, fft_buf, fft_in);
			do_fft(fft_in, fft_hist_next());

				/* only advance half a buffer so window
				 * doesn't lose detail ("overlap-add") */
			memmove(fft_buf, &fft_buf[fft_size/2],
				(sizeof fft_buf[0])*fft_size/2);
			fft_buf_i = fft_size/2;
			did_any++;
		}
	}

	if (did_any) fft_display(bitmap_ptr);
}


void
fft_redraw(jint *bitmap_ptr)
{
	fft_legend_width = width/10;
	fft_display(bitmap_ptr);		/* display spectrum */
	fft_draw_slider(bitmap_ptr);
	if (fft_grid_freq) {
		fft_draw_freqs(bitmap_ptr);
	} else {
		fft_draw_piano(bitmap_ptr);
	}
	fft_draw_seconds(bitmap_ptr);
	window_free(curr_window);
	curr_window = window_init(wk_blackman, fft_size);
}
//XXX - fft_hist needs to reset when turning on fft mode, or does it really?
