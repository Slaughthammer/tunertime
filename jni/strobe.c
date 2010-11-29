#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <math.h>
#include <ggl.h>
#include <assert.h>
#include <jni.h>

int strobes_paused = 0;


static void
ggl_strobe_redraw(struct ggl_widget *widget)
{
	struct ggl_strobe *s = (struct ggl_strobe *)widget;
	/* pass through */
	if (s->image && s->image->parent.redraw) {
		s->image->parent.redraw((struct ggl_widget *)s->image);
	}
}

static void
ggl_strobe_resize(struct ggl_widget *widget, ggl_coord *points, int num_points)
{
	struct ggl_strobe *s = (struct ggl_strobe *)widget;

	/* update */
	if (num_points == 2) {
		s->parent.pos.x = points[0].x;
		s->parent.pos.y = points[0].y;
		s->parent.width = points[1].x - points[0].x;
		s->parent.height = points[1].y - points[0].y;
	}

	/* pass through */
	if (s->image && s->image->parent.redraw) {
		s->image->parent.resize((struct ggl_widget *)s->image, points, num_points);
	}

	/* then recompute any precomps.. */
	ggl_strobe_recalc(s);
}

static void
ggl_strobe_reattach(struct ggl_widget *widget)
{
	struct ggl_strobe *s = (struct ggl_strobe *)widget;
	if (s->image) {
		s->image->parent.root = s->parent.root;
		if (s->image->parent.reattach) {
			s->image->parent.reattach((struct ggl_widget *)s->image);
		}
	}
}


struct ggl_strobe *
ggl_strobe_new(void)
{
	struct ggl_strobe *ret;
	ret = safe_malloc(sizeof *ret);
	memset(ret, 0, sizeof *ret);
	ret->image = ggl_image_new();
	ret->parent.pos = ret->image->parent.pos;
	ret->parent.width = ret->image->parent.width;
	ret->parent.height = ret->image->parent.height;
	ret->parent.root = ret->image->parent.root;
	ret->parent.redraw = ggl_strobe_redraw;
	ret->parent.resize = ggl_strobe_resize;
	ret->parent.mouse = NULL;
	ret->parent.destroy = NULL; /* could probably stand to catch, *shrug* */
	ret->parent.reattach = ggl_strobe_reattach;
	ret->label[0] = 0;
	ret->image->label = ret->label;
	ret->image->label_pos = GGL_GRAV_SW;
	ret->image->font = gf_normal;
	ret->image->color = gc_peach;
	ret->next = active_strobes;
	ret->freq = 440.0;
	active_strobes = ret;
	ggl_strobe_recalc(ret);
	return ret;
}

static void
filter_recalc(struct ggl_strobe *s)
{
	double R,K;
	double R2;
	double c;
	double freq = s->freq;
	double bandwidth = freq/20.0;

	freq /= ((double)snd_rate);
	bandwidth /= ((double)snd_rate);

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

void
ggl_strobe_recalc(struct ggl_strobe *s)
{
	sprintf(s->label, "%.1f", s->freq);

	filter_recalc(s);

	s->msamps_per_col = (snd_rate*1000) / s->freq;

	s->multiplier = (s->freq + s->parent.width) / s->parent.width;
	if (s->multiplier < 1) {
		s->multiplier = 1;
	}
}

static void
strobe_filter(struct ggl_strobe *s)
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
filled_rect(XImage *image, int x1, int y1, int x2, int y2, unsigned color)
{
	int x,y;
	for (y = y1; y < y2; y++) {
		for (x = x1; x < x2; x++) {
			XPutPixel(image, x, y, color);
		}
	}
}

#define NUM_CLEAR 25	/* number of columns to clear at once */
static void
clear_col(struct ggl_strobe *s, int col)
{
	int start_x, stop_x;
	if (col < s->last_cleared_col) {
		if (col+(2*NUM_CLEAR) < s->last_cleared_col) {
			/* must have looped to zero */
			col += s->parent.width;
		} else {
			/* nothing to do, already cleared */
			return;
		}
	} else if (col+(2*NUM_CLEAR) >= s->last_cleared_col+s->parent.width) {
		/* this column has already been cleared, just last_cleared_col
		 * has looped to zero */
		return;
	}
		
	start_x = s->last_cleared_col;
	stop_x = col+NUM_CLEAR;
	s->last_cleared_col = (stop_x % s->parent.width);

	if (stop_x > s->parent.width) {
		if (stop_x >= s->parent.width*2) {
			start_x = 0;
			stop_x = 0;
		}
		filled_rect(s->image->image, start_x, 0, s->parent.width, s->parent.height, X_COLOR_VALUE(0,0,0));
		start_x = 0;
		stop_x -= s->parent.width;
	}

	if (start_x == stop_x) {
		return;
	}
	filled_rect(s->image->image, start_x, 0, stop_x+1, s->parent.height, X_COLOR_VALUE(0,0,0));
}

static void
strobe_drawnew(struct ggl_strobe *s)
{
	static int exptab[256] = { -1, };
	unsigned pos = s->prev_pos;
	unsigned pos_width = s->msamps_per_col*s->parent.width;
	unsigned color;
	int samp_max = s->samp_max;
	int samp_divider = 1+(samp_max/256);
	int last_x = -1;
	int x,y;
	int i;
	int multiplier = s->multiplier;

	if (exptab[0] == -1) {
#define LOG_STEEP 40.0
		int i;
		double scale = 65534.0/log(LOG_STEEP*256.0);
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
		y = ((pos % s->msamps_per_col) * s->parent.height) / s->msamps_per_col;
		if (x != last_x) {
			clear_col(s, x);
			last_x = x;
		}

		if (samp >= 0) {
			int e = exptab[samp];
			color = X_COLOR_VALUE(e, e/2, e/2);
		} else {
			int e = exptab[-samp];
			color = X_COLOR_VALUE(e/2, e, e/2);
		}
		XPutPixel(s->image->image, x, y, color);

		s->curr_samp++;
		i = s->curr_samp%SND_BUFSAMPS;
	}
	s->prev_pos = pos;
	s->samp_max = samp_max;
}

/* draw a horizontal tic mark at 1 second into the buffer */
static void
strobe_draw_htic(struct ggl_strobe *s)
{
	int x, y;
	int step;
	step = s->freq / s->multiplier;
	for (x = step; x < s->parent.width; x += step) {
		for (y = (s->parent.height*8)/10; y < s->parent.height; y++) {
			XPutPixel(s->image->image, x, y,
				X_COLOR_VALUE(0xffff,0xffff,0xaaaa));
		}
	}
}

static void
ggl_strobe_newdata(struct ggl_strobe *s)
{
	if (s->curr_samp == snd_ofs) {
		/* not really any new data */
		return;
	}
	strobe_filter(s);
	strobe_drawnew(s);
	strobe_draw_htic(s);
	s->image->parent.redraw((struct ggl_widget *)s->image);
}

void
strobe_newdata(void)
{
	struct ggl_strobe *s;
	if (strobes_paused) {
		return;
	}
	if (!active_strobes) {
		return;
	}
	for (s = active_strobes; s; s = s->next) {
		ggl_strobe_newdata(s);
	}
}
