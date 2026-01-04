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
int fft_height = FFT_RANGE_MULT;	/* size of freq window we are viewing */
int fft_xoffset = 0;			/* offset, out of FFT_RANGE_MULT */
int fft_yoffset = 0;			/* offset, out of FFT_RANGE_MULT */
int fft_grid_freq = 0;

static double fft_buf[MAX_FFT_SIZE];
static int fft_buf_i = 0;			/* output */
static double fft_sr[32];
static double fft_si[32];
static struct window_rec *curr_window = NULL;

static int fft_legend_width = 0;
static int fft_legend_height = 0;


static void find_peaks(double *spect, double *peaks);


/* allocate enough space for about 60 seconds */
/* note that head/tail are in units of fft_size/2 (the spectrum is half the size
 * of fft_size, because we discard the phase information) */
#define FFT_HIST_BUFSZ (sample_rate*60)
static double *fft_hist_buf;
static int fft_hist_head = 0, fft_hist_tail = 0;
static int fft_hist_count = 0;		/* BUFSZ / (fft_size/2) */
static double (*fft_hist_peaks)[MAX_PEAKS] = NULL;

static void
fft_hist_init(void)
{
	int old_count = fft_hist_count;
	if (!fft_hist_buf) {
		fft_hist_buf = malloc(FFT_HIST_BUFSZ*sizeof *fft_hist_buf);
	}
	fft_hist_count = FFT_HIST_BUFSZ / (fft_size/2);
	if (old_count == fft_hist_count) return;

	free(fft_hist_peaks);
	fft_hist_peaks = malloc(fft_hist_count * sizeof fft_hist_peaks[0]);
	fft_hist_head = 0; fft_hist_tail = 0;
}

static void
fft_hist_next(double **spect_p, double **peaks_p)
{
	fft_hist_tail = (fft_hist_tail+1) % fft_hist_count;

	if (fft_hist_tail == fft_hist_head) {
		/* running into back of head */
		fft_hist_head = ((fft_hist_head + 1) % fft_hist_count);
	}
	*spect_p = &fft_hist_buf[fft_hist_tail*fft_size/2];
	*peaks_p = &fft_hist_peaks[fft_hist_tail][0];
}

/* ofs is negative frames counting backwards from tail */
static void
fft_hist_get(int ofs, double **spect_p, double **peaks_p)
{
	int avail = ((fft_hist_tail > fft_hist_head)
			? (fft_hist_tail - fft_hist_head)
			: (fft_hist_tail + fft_hist_count - fft_hist_head));
	if (avail < ofs) {
		*spect_p = NULL;
		*peaks_p = NULL;
		return;
	}
	int n = ((fft_hist_tail + fft_hist_count - ofs) % fft_hist_count);
	*spect_p = &fft_hist_buf[n * fft_size/2];
	*peaks_p = &fft_hist_peaks[n][0];
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
fft_draw_xslider(jint *bitmap_ptr)
{
	int dw = width-fft_legend_width;
	int x1 = (dw * fft_xoffset) / FFT_RANGE_MULT;
	int x2 = (dw * (fft_xoffset+fft_width)) / FFT_RANGE_MULT;
	int y1 = height-fft_legend_height;

	if (x1 < 0) x1 = 0;
	if (x1 >= dw) x1 = dw-1;
	if (x2 <= x1) x2 = x1+1;
	if (x2 > dw) x2 = dw;
	if (x1 > 0) {
		filled_rect(bitmap_ptr, 0,y1,x1,height, 0xFFFF0000);
	}
	filled_rect(bitmap_ptr, x1,y1,x2,height, 0xFF00FF00);
	if (x2 < dw) {
		filled_rect(bitmap_ptr, x2,y1,dw,height, 0xFFFF0000);
	}
}

static void
fft_draw_yslider(jint *bitmap_ptr)
{
	int dh = height-fft_legend_height;
	int y1 = (fft_yoffset*dh) / FFT_RANGE_MULT;
	int y2 = ((fft_yoffset+fft_height)*dh)/FFT_RANGE_MULT;
	int x1 = width-(fft_legend_width/3);

	if (y1 < 0) y1 = 0;
	if (y1 >= dh) y1 = dh-1;
	if (y2 < 0) y2 = 0;
	if (y2 > dh) y2 = dh;
	if (y1 > 0) {
		filled_rect(bitmap_ptr, x1,0,width,y1, 0xFFFF0000);
	}
	filled_rect(bitmap_ptr, x1,y1,width,y2, 0xFF00FF00);
	if (y2 < dh) {
		filled_rect(bitmap_ptr, x1,y2,width,dh, 0xFFFF0000);
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
	fft_hist_init();
}

static int grid_y[SEVEN_CHARS];
static int num_grid_y = 0;

#define COMPUTE_BINS \
	tot_bins = fft_size/2; \
	first_bin = ((fft_yoffset*tot_bins)/FFT_RANGE_MULT); \
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
	int dh = height-fft_legend_height;
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
		y = (int)(((bin-(double)first_bin)*(double)dh) / (double)num_bins);

		w = SEVEN_WID*strlen(label);
		y0 = y - (w / 2);
		y1 = y - 1;
		y2 = y + 2;
		y3 = y0 + w;
		if (y1 < 0) y1 = 0;
		if (y2 > dh) y2 = dh;

		filled_rect(bitmap_ptr, x2, y1, x3, y2, 0xFFFFFFFF);
		xor_hline(bitmap_ptr, 0, x1, y);
		grid_y[num_grid_y++] = y;
		if ((y0 >= 0) && (y3 < dh)) {
			for (i = 0; label[i]; i++) {
				seven_seg(bitmap_ptr,
					x1 + (SEVEN_WID*i), y0, label[i],
					0xFFAAAAFF);
			}
		}
	}
}

static void
fft_draw_grids(jint *bitmap_ptr)
{
	int disp_width = width-fft_legend_width;
	int disp_height = height-fft_legend_height;
	int i;
	for (i = 0; i < num_grid_y; i++) {
		xor_hline(bitmap_ptr, 0, disp_width, grid_y[i]);
	}

	double frac_ofs = (fft_xoffset+fft_width) / (double)FFT_RANGE_MULT;
	for (i = 1; ; i++) {
		double frac = (FFT_HIST_BUFSZ-(i*sample_rate)) / (double)FFT_HIST_BUFSZ;
		if (frac > frac_ofs) continue;
		frac = frac_ofs - frac;
		frac /= fft_width / (double)FFT_RANGE_MULT;
		int x = disp_width - (frac * disp_width);
		if (x <= 0) break;
		xor_vline(bitmap_ptr, x, 0, disp_height);
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
fft_display_peaks(jint *bitmap_ptr, int x1, int x2, double *peaks)
{
	int tot_bins, first_bin, num_bins;
	int dh = height-fft_legend_height;
	int i;

	if (!peaks) return;

	COMPUTE_BINS

	for (i = 0; i < MAX_PEAKS; i++) {
		if (peaks[i] == 0.0) break;
		double bin = peaks[i];
		if ((int)bin < first_bin) continue;
		if ((int)bin >= first_bin+num_bins) break;
		int y = (int)(((bin-(double)first_bin)*(double)dh) / (double)num_bins);
		filled_rect(bitmap_ptr, x1,y,x2,y+1, 0xFFCCCCFF);

		
	}
}

static void
fft_display_col(jint *bitmap_ptr, int x1, int x2, double *fft_data)
{
	int dh = height - fft_legend_height;
	int tot_bins, first_bin, num_bins;
	int i;
	int yn;
	int incr;
	double dincr;

	COMPUTE_BINS

	if ((dh <= 0) || !num_bins || !fft_data) {
		filled_rect(bitmap_ptr, x1, 0, x2, dh, 0xFF222222);
		return;
	}

	incr = num_bins / dh;
	if (incr < 1) incr = 1;

	yn = (dh+(num_bins-1))/num_bins;
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
		y1 = (i*dh)/num_bins;
		filled_rect(bitmap_ptr, x1, y1, x2, y1+yn, color);
	}
}

static void
fft_display(jint *bitmap_ptr)
{
	int disp_width = width-fft_legend_width;
	double cols = (FFT_RANGE_MULT*disp_width) /
			(double)(fft_width*fft_hist_count);
	double hist_ofs = ((FFT_RANGE_MULT-(fft_xoffset+fft_width))*
			   fft_hist_count) / (double)FFT_RANGE_MULT;
	int i;
	for (i = 0; ; i++) {
		double *spect, *peaks;
		int i_prime = i+hist_ofs;
		int x1 = disp_width - (cols * (1.0+(double)i_prime-hist_ofs));
		int x2 = disp_width - (cols * ((double)i_prime-hist_ofs));
		if (x2 < 1) break;
		if (x1 >= disp_width) continue;
		if (x1 < 0) x1 = 0;
		if (x2 <= x1) x2 = x1+1;
		fft_hist_get(i_prime, &spect, &peaks);
		fft_display_col(bitmap_ptr, x1, x2, spect);
		fft_display_peaks(bitmap_ptr, x1, x2, peaks);
	}

	fft_draw_grids(bitmap_ptr);
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
			double *spect, *peaks;
			fft_hist_next(&spect, &peaks);
			window_apply(curr_window, fft_buf, fft_in);
			do_fft(fft_in, spect);
			find_peaks(spect, peaks);

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
	fft_legend_width = width/10;		/* space for freq legend */
	fft_legend_height = height/30;		/* space for time legend */
	fft_draw_xslider(bitmap_ptr);
	fft_draw_yslider(bitmap_ptr);
	if (fft_grid_freq) {
		fft_draw_freqs(bitmap_ptr);
	} else {
		fft_draw_piano(bitmap_ptr);
	}
	fft_display(bitmap_ptr);		/* display spectrum */
	window_free(curr_window);
	curr_window = window_init(wk_blackman, fft_size);
}


/* peak detection: */

#define MOVAVG_CENTS 900	/* moving average window */
static double movavg[MAX_FFT_SIZE];
#define PEAK_BOT 0.5		/* coeff for movavg[y] for bottom of peak */
#define PEAK_TOP 2.0		/* coeff for movang[y] for top of lowest peak */


static void
accum_movavg(int bin, double val)
{
	static int last_filled = 0;
	if (bin == -1) {
		movavg[0] = val;
		last_filled = 0;
	} else {
		if (bin < 0) bin = 0;
		if (bin > fft_size/2-1) bin = fft_size/2-1;
		if (bin <= last_filled) {
			/* repeating an old bin */
		} else {
			for (; last_filled < bin-1; last_filled++) {
				movavg[last_filled+1] = movavg[last_filled];
			}
			movavg[bin] = val;
			last_filled = bin;
		}
	}
}

static void
compute_movavg(double *spect, int spect_bins)
{
	double range_frac = exp2(((double)MOVAVG_CENTS)/1200.0);
	double sum = 0.0;
	int sum_y1 = 1, sum_y2 = 1;		/* range covered by sum */
			/* range is ideally from freq .. range_frac*freq */
	int y;

	/* skip the 0 bin, which represents DC (freq==0) and would throw off the
	 * math here */
	accum_movavg(-1, 0.0);
	for (y = 1; y < spect_bins; y++) {
		sum += spect[y];
		sum_y2 = y;
		int new_y1 = (((double)sum_y2)/range_frac)+0.5;
		if (sum_y1 < new_y1) {
			/* subtract elements that window has passed */
			int i;
			for (i = sum_y1; i < new_y1; i++) {
				sum -= spect[i];
			}
			sum_y1 = new_y1;
		}
/* bin = sum_y1*exp2(log2(sum_y2/sum_y1)/2) = sum_y1*sqrt(sum_y2/sum_y1) */
		accum_movavg(/*bin=*/sqrt(sum_y1*sum_y2)+0.5,
			     /*val=*/sum/(double)((sum_y2-sum_y1)+1));
	}
	while (sum_y1 < sum_y2) {
		sum -= spect[sum_y1++];
		accum_movavg(/*bin=*/sqrt(sum_y1*sum_y2)+0.5,
			     /*val=*/sum/(double)((sum_y2-sum_y1)+1));
	}
	accum_movavg(spect_bins-1, 0);
}

/* finds a peak and returns its center (in bins) */
/* *sb_y_p indicates which bin to start the search at, and it is incremented to
 * avoid re-discovering previous peaks. */
static double
find_peak_prime(double *spect, int spect_bins, int *sb_y_p)
{
	int peak_seen = 0;
	int y = *sb_y_p;
	int start_y = y;
	for (; y < spect_bins; y++) {
		double v = spect[y] / movavg[y];
		if (v < PEAK_BOT) {
			if (peak_seen) {
				int stop_y = y-1;
				double sum = 0.0;
				double out_of = 0.0;
				int i;
				for (i = start_y; i <= stop_y; i++) {
					v = spect[i];
					sum += i*v;
					out_of += v;
				}
				*sb_y_p = y;
				return sum / out_of;
			} else {
				start_y = y+1;	/* next one might be it */
			}
		} else if (v >= PEAK_TOP) {
			peak_seen = 1;
		}
	}
	*sb_y_p = y;
	return 0.0;
}

/* Fills in up to MAX_PEAKS members of peaks (0.0-terminated) with an ordered
 * list of frequencies where peaks were detected */
static void
find_peaks(double *spect, double *peaks)
{
	int num_peaks = 0;
	int spect_bins = fft_size/2;

	compute_movavg(spect, spect_bins);
	int sb_y = 1;
	while (num_peaks < MAX_PEAKS-1) {
		double val = find_peak_prime(spect, spect_bins, &sb_y);
		if (!val) break;
		peaks[num_peaks++] = val;
	}

	peaks[num_peaks] = 0.0;
}
//XXX - turn the display upside down, higher frequencies should be visually higher
