#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <math.h>
#include <assert.h>
#include "interface.h"

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

	unsigned xsamps_per_col;	/* 100,000th samples per column */
	unsigned prev_pos;		/* pos within column of last point */
	unsigned prev_x, prev_y;	/* display location of last point */
	unsigned prev_color;		/* display color of last point */
	int samp_max;			/* highest amplitude seen so far */
	unsigned multiplier;		/* horizontal scale */
	int last_cleared_col;
};


int base_cents;
int harm0_mult, harm1_mult, harm2_mult;

static struct strobe_rec strobes[NUM_STROBES];


void
strobe_init(void)
{
	memset(strobes, 0, sizeof strobes);
}


static unsigned color_red(int x) {
	return COLOR_VALUE(x, x/2, x/2);
}
static unsigned color_green(int x) {
	return COLOR_VALUE(x/2, x, x/2);
}
static unsigned color_white(int x) {
	x = 0x80 + (x/2);
	return COLOR_VALUE(x, x, x);
}
static unsigned color_black(int x) {
	x = 0x80 - (x/2);
	return COLOR_VALUE(x, x, x);
}
static unsigned color_blue(int x) {
	return COLOR_VALUE(x/2, x/2, x);
}
static unsigned color_yellow(int x) {
	return COLOR_VALUE(x, x, x/2);
}
static unsigned color_slate_dark(int x) {
	return COLOR_VALUE(x/6, (x*2)/9, x/3);
}
static unsigned color_slate_light(int x) {
	return COLOR_VALUE((x*29)/32, (x*29)/32, (x*15)/16);
}

static unsigned (*color1)(int x) = color_slate_dark;
static unsigned (*color2)(int x) = color_slate_light;

void
strobe_color(const char *s)
{
	if (!s || !strcmp(s, "slate")) {
		color1 = color_slate_dark;
		color2 = color_slate_light;
	} else if (!strcmp(s, "redgreen")) {
		color1 = color_red;
		color2 = color_green;
	} else if (!strcmp(s, "blackwhite")) {
		color1 = color_black;
		color2 = color_white;
	} else if (!strcmp(s, "blueyellow")) {
		color1 = color_blue;
		color2 = color_yellow;
	} else if (!strcmp(s, "redblue")) {
		color1 = color_red;
		color2 = color_blue;
	} else {
		color1 = color_slate_dark;
		color2 = color_slate_light;
	}
}


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
strobe_recalc_single(struct strobe_rec *s)
{
	filter_recalc(s);

	double t = ((double)sample_rate) / (double)s->freq;
	s->xsamps_per_col = t*100000.0+0.5;

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

	for (i = 0; i < snd_buflen; i++) {
		double in = snd_buf[i];
		y0 = ((a0*in) + (a1*x1) + (a2*x2) + (b1*y1) + (b2*y2));
		x2 = x1;
		x1 = in;
		y2 = y1;
		y1 = y0;
		s->filtered[i] = y0;
	}

	s->x1 = x1; s->x2 = x2; s->y1 = y1; s->y2 = y2;
}


#define NUM_CLEAR 25	/* number of columns to clear at once */
static void
clear_col(jint *bitmap_ptr, struct strobe_rec *s, int col)
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
		filled_rect(bitmap_ptr,
			start_x, s->y, width, s->y+s->height-1, 0xFF000000);
		start_x = 0;
		stop_x -= width;
	}

	if (start_x == stop_x) {
		return;
	}
	filled_rect(bitmap_ptr,
		start_x, s->y, stop_x+1, s->y+s->height-1, 0xFF000000);
}

static void
strobe_drawnew(jint *bitmap_ptr, struct strobe_rec *s)
{
	static int exptab[256] = { -1, };
	unsigned pos = s->prev_pos;
	unsigned color, last_color = s->prev_color;
	int samp_max = (s->samp_max*9)/10;
	int samp_divider = 1+(samp_max/256);
	unsigned last_x = s->prev_x, last_y = s->prev_y;
	unsigned h = s->height-1;
	unsigned x = last_x, y;
	int i;
	unsigned multiplier = s->multiplier;
	unsigned xsamps_per_pos = s->xsamps_per_col * multiplier;
	unsigned xsamps_per_col = s->xsamps_per_col;
	unsigned msamps_per_col = s->xsamps_per_col/100;

	if (!width) {				/* prevent divide by zero */
		return;
	}

	if (exptab[0] == -1) {
#define LOG_STEEP 40.0
		int i;
		double scale = 254.0/log(LOG_STEEP*256.0);
		for (i = 0; i < 256; i++) {
			exptab[i] = log((i+1.0)*LOG_STEEP)*scale;
		}
	}

	for (i = 0; i < snd_buflen; i++) {
		int samp = s->filtered[i];

		if (abs(samp) > samp_max) {
			samp_max = abs(samp);
			samp_divider = 1+(samp_max/256);
		}
		samp /= samp_divider;

		pos += 100000;
		while (pos > xsamps_per_pos) {
			pos -= xsamps_per_pos;
			x++;
			x %= width;
		}
		if (pos > xsamps_per_col) {
			/* multiplier must be greater than 1, skipping this
			 * column.. */
			continue;
		}
		y = ((pos/100) * h) / msamps_per_col;
		if (x != last_x) {
			clear_col(bitmap_ptr, s, x);
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
			color = color1(exptab[samp]);
		} else {
			color = color2(exptab[-samp]);
		}
		DRAW_PIXEL(x, y+s->y, color);

		last_y = y;
		last_color = color;
	}
	s->prev_pos = pos;
	s->prev_x = last_x;
	s->prev_y = last_y;
	s->prev_color = last_color;
	s->samp_max = samp_max;
}

/* draw a vertical tic mark at 1 second into the buffer */
static void
strobe_draw_htic(jint *bitmap_ptr, struct strobe_rec *s)
{
	int x;
	int step;
	step = s->freq / s->multiplier;
	for (x = step; x < width; x += step) {
		int h = (s->height*3)/10;
		vline(bitmap_ptr, x, s->y, s->y+h, 0xFFFFB300);
		vline(bitmap_ptr, x-1, s->y, s->y+h, 0xFFFFB300);
		vline(bitmap_ptr, x-2, s->y, s->y+h, 0xFFFFB300);
	}
}


/* just draws the separators...everything else is redrawn by strobe_newdata */
void
strobe_redraw(jint *bitmap_ptr)
{
	int h = strobes[0].height;
	int y = h-1;
	int i;

	for (i = 0; i < NUM_STROBES-1; i++) {
		hline(bitmap_ptr, 0, y, width, 0xFF2E343C);
		y += h;
	}
}

void
strobe_newdata(jint *bitmap_ptr)
{
	int i;
	for (i = 0; i < NUM_STROBES; i++) {
		struct strobe_rec *s = &strobes[i];
		if (snd_buflen == 0) {
			/* not really any new data */
			continue;
		}
		strobe_filter(s);
		strobe_drawnew(bitmap_ptr, s);
		strobe_draw_htic(bitmap_ptr, s);
	}
}


static void
update_labels(jobject this)
{
	char lab1[100], lab2[100], lab3[100];
	char *labels[NUM_STROBES] = { lab1, lab2, lab3 };
	int i;
	char pianoname[10];
	static const char notes[12][3] =
	    { "A", "A#", "B", "C", "C#", "D", "D#", "E", "F", "F#", "G", "G#" };
	int note = ((base_cents+50)/100)%12;

	for (i = 0; i < NUM_STROBES; i++) {
		int mult = ((i==0) ? harm0_mult
				   : ((i==1) ? harm1_mult : harm2_mult));
		char *ext = ((mult==1) ? "st" : ((mult==2) ? "nd" :
				((mult==3) ? "rd" : "th")));
		sprintf(labels[i], "%2d%s %7.1f", mult, ext, strobes[i].freq);
	}
	set_labels(this, labels);
	i = ((base_cents+50)/100)+1;
	if ((i < 1) || (i > 88)) {
		sprintf(pianoname, "%s__", notes[note]);
	} else {
		sprintf(pianoname, "%s%d", notes[note], i);
	}
	set_pianoname(this, pianoname);
}


void
strobe_recalculate(jobject this)
{
	int i;
	int cents = base_cents;
	int sheight = height/NUM_STROBES;
	int y = 0;

	for (i = 0; i < NUM_STROBES; i++) {
		int mult = ((i==0) ? harm0_mult
				   : ((i==1) ? harm1_mult : harm2_mult));
		strobes[i].prev_pos = 0;
		strobes[i].y = y;
		strobes[i].height = sheight;
		strobes[i].freq = cents_to_freq(this, cents, mult);
		strobe_recalc_single(&strobes[i]);

		y += sheight;
	}
	update_labels(this);
}
