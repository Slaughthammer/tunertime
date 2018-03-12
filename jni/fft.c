#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <math.h>
#include <assert.h>
#include "interface.h"
#include "window.h"

int fft_mode = 0;
int fft_size = 8192;
int fft_width = FFT_RANGE_MULT;		/* size of freq window we are viewing */
int fft_offset = 0;			/* offset, out of FFT_RANGE_MULT */
int fft_grid_freq = 0;

static double fft_buf[MAX_FFT_SIZE];
static double fft_r[MAX_FFT_SIZE];
static double fft_i[MAX_FFT_SIZE];
static int fft_buf_i = 0;			/* output */
static double fft_sr[32];
static double fft_si[32];
static struct window_rec *curr_window = NULL;

static int fft_y = 0;
static int fft_legend_height = 0;


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

/* applies the window to input in fft_buf, producing output in fft_r */
static void
do_fft_window(void)
{
	memcpy(fft_r, fft_buf, fft_size*(sizeof fft_buf[0]));
	window_apply(curr_window, fft_r);
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
fft_draw_slider(jint *bitmap_ptr)
{
	int x1 = (fft_offset*width) / FFT_RANGE_MULT;
	int x2 = ((fft_offset+fft_width)*width)/FFT_RANGE_MULT;
	int y1 = height-(fft_legend_height/3);

	if (x1 < 0) x1 = 0;
	if (x1 >= width) x1 = width-1;
	if (x2 < 0) x2 = 0;
	if (x2 > width) x2 = width;
	if (x1 > 0) {
		filled_rect(bitmap_ptr, 0,y1,x1,height, 0xFFFF0000);
	}
	filled_rect(bitmap_ptr, x1,y1,x2,height, 0xFF00FF00);
	if (x2 < width) {
		filled_rect(bitmap_ptr, x2,y1,width,height, 0xFFFF0000);
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
fft_draw_piano(jint *bitmap_ptr)
{
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
		static const char *(names[12]) = { "C", NULL, "D", NULL, "E", "F", NULL, "G", NULL, "A", NULL, "B" };
		const char *name = names[(i+9) % 12];

		freq = piano_freqs[i];

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
			filled_rect(bitmap_ptr, x1, y2, x2, y3, 0xFFFFFFFF);
			if (x0 >= last_x) {
				seven_seg(bitmap_ptr, x0, y1,
					name[0], 0xFFAAAAFF);
				seven_seg(bitmap_ptr, x0+SEVEN_WID, y1,
					((i+9)/12)+'0', 0xFFAAAAFF);
				last_x = x0 + (SEVEN_WID*2);
				xor_vline(bitmap_ptr, x, 0, y1);
				grid_x[num_grid_x++] = x;
			}
		} else {
			filled_rect(bitmap_ptr, x1, y2, x2, y3, 0xFF666666);
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

		filled_rect(bitmap_ptr, x1, y2, x2, y3, 0xFFFFFFFF);
		xor_vline(bitmap_ptr, x, 0, y1);
		grid_x[num_grid_x++] = x;
		if ((x0 >= 0) && (x3 < width)) {
			for (i = 0; label[i]; i++) {
				seven_seg(bitmap_ptr,
					x0 + (SEVEN_WID*i), y1, label[i],
					0xFFAAAAFF);
			}
		}
	}
}

static void
fft_draw_seconds(jint *bitmap_ptr)
{
	int y, y1;

	y1 = height - fft_legend_height;

	for (y = (y1/FFT_SECONDS); y < (y1-(FFT_SECONDS*2));
	     y += (y1/FFT_SECONDS)) {
		xor_hline(bitmap_ptr, 0, width, y);
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


static void
do_fft_display(jint *bitmap_ptr)
{
	int tot_bins, first_bin, num_bins;
	int i;
	int y1, y2, xn;
	int incr;
	double dincr;
	int disp_height = height-fft_legend_height;
	int rows;

	y1 = fft_y;
	rows = ((disp_height*fft_size) / (2*FFT_SECONDS*sample_rate));
	if (rows < 1) {
		rows = 1;
	}
	y2 = y1 + rows;
	if (y2 > disp_height) y2 = disp_height;

	COMPUTE_BINS

	if (!width || !num_bins) {		/* prevent divide by zero */
		return;
	}

	incr = num_bins / width;
	if (incr < 1) incr = 1;

	xn = (width+(num_bins-1))/num_bins;
	if (xn < 1) xn = 1;

		/* normalize the data, and compute the average */
	for (i = 0; i < num_bins; i++) {
		int n = i+first_bin;
		double r = fft_r[n];
		double i = fft_i[n];
		double t = sqrt(r*r+i*i);
		fft_r[n] = t;
	}

	dincr = incr;
		/* draw fft data */
	for (i = 0; i < num_bins; i+=incr) {
		int x1;
		double val = 0;
		unsigned color;
		int j;
		for (j = 0; j < incr; j++) {
			val += fft_r[i+j+first_bin];
		}
		val /= dincr;
		color = color_for_value(val);
		x1 = (i*width)/num_bins;
		filled_rect(bitmap_ptr, x1, y1, x1+xn, y2, color);
	}
	hline(bitmap_ptr, 0, (y2+0)%disp_height, width, 0xFF000000);
	hline(bitmap_ptr, 0, (y2+1)%disp_height, width, 0xFF000000);
	hline(bitmap_ptr, 0, (y2+2)%disp_height, width, 0xFF000000);
	hline(bitmap_ptr, 0, (y2+3)%disp_height, width, 0xFF000000);
	hline(bitmap_ptr, 0, (y2+4)%disp_height, width, 0xFF000000);

		/* draw grid */
	for (i = 0; i < num_grid_x; i++) {
		xor_vline(bitmap_ptr, grid_x[i], y1, y2);
	}
	for (i = (disp_height/FFT_SECONDS); i < (disp_height-(FFT_SECONDS*2));
	     i += (disp_height/FFT_SECONDS)) {
		if ((y1 <= i) && (y2 > i)) {
			xor_hline(bitmap_ptr, 0, width, i);
		}
	}

	fft_y = y2;
	if (fft_y >= disp_height) {
		fft_y = 0;
	}
}

void
fft_newdata(jint *bitmap_ptr)
{
	int i;

	for (i = 0; i < snd_buflen; i++) {
		fft_buf[fft_buf_i++] = ((double)snd_buf[i]) / 32768.0;
		if (fft_buf_i >= fft_size) {
			do_fft_window();
			do_fft();
			do_fft_display(bitmap_ptr);

				/* only advance half a buffer so window
				 * doesn't lose detail */
			memmove(fft_buf, &fft_buf[fft_size/2],
				(sizeof fft_buf[0])*fft_size/2);
			fft_buf_i = fft_size/2;
		}
	}
}


void
fft_redraw(jint *bitmap_ptr)
{
	fft_y = 0;
	fft_legend_height = height/10;
	fft_draw_slider(bitmap_ptr);
	if (fft_grid_freq) {
		fft_draw_freqs(bitmap_ptr);
	} else {
		fft_draw_piano(bitmap_ptr);
	}
	fft_draw_seconds(bitmap_ptr);
	memset(fft_r, 0, sizeof fft_r);
	window_free(curr_window);
	curr_window = window_init(wk_blackman, fft_size);
}
