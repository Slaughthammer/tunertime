#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <math.h>
#include <assert.h>
#include <jni.h>

/* this should match the value in src/org/galexander/tunertime/Strobe.java */
#define SAMPS_PER_READ 2048
/* this must be bigger than SAMPS_PER_READ */
#define SND_BUFSAMPS 4096

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


static int sample_rate;
static double base_freq;
static int width;
static int height;
static struct strobe_rec strobes[NUM_STROBES];
static short snd_buf[SND_BUFSAMPS];
static int snd_ofs;

static jint *bitmap_ptr;


static void println(const char *fmt, ...);


#define COLOR_VALUE(r,g,b) (((r)<<16)|((g)<<8)|(b))
#define DRAW_PIXEL(x,y,c) bitmap_ptr[(x)+((y)*width)] = (c)


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
println("recalc inner: %f,%f,%f,%f,%f", s->a0, s->a1, s->a2, s->b1, s->b2);
}

static void
strobe_recalc(struct strobe_rec *s)
{
/* XXX - add label display back in someday */
//	sprintf(s->label, "%.1f", s->freq);

	filter_recalc(s);

	s->msamps_per_col = (sample_rate*1000) / s->freq;

	s->multiplier = (s->freq + width) / width;
	if (s->multiplier < 1) {
		s->multiplier = 1;
	}
println("recalc outer: %d,%d",(int)s->msamps_per_col, (int)s->multiplier);
}

static void
recalculate(void)
{
	int i;
	double freq = base_freq;
	int sheight = height/NUM_STROBES;
	int y = 0;
println("recalculate:%f (%d,%d)",base_freq, width,height);
	for (i = 0; i < NUM_STROBES; i++) {
		strobes[i].y = y;
		strobes[i].height = sheight;
		strobes[i].freq = freq;
		strobe_recalc(&strobes[i]);
		freq *= 2.0;
		y += sheight;
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
//XXXprintln("[%d]in=%f,y0=%f", (int)i, in, y0);
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
		}

		if (samp >= 0) {
			int e = exptab[samp];
			color = COLOR_VALUE(e, e/2, e/2);
		} else {
			int e = exptab[-samp];
			color = COLOR_VALUE(e/2, e, e/2);
		}
		DRAW_PIXEL(x, y+s->y, color);

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

void
newdata(void)
{
	int i;
#if 0 /* XXX - bring back pause?? */
	if (strobes_paused) {
		return;
	}
#endif
	for (i = 0; i < NUM_STROBES; i++) {
		strobe_newdata(&strobes[i]);
	}
}



static JNIEnv *env;

static jclass cl_system;
static jclass cl_printstream;
static jclass cl_strobe;
static jclass cl_audiorecord;

static jmethodID mid_println;
static jmethodID mid_st_do_draw;
static jmethodID mid_st_isinterrupted;
static jmethodID mid_ar_getsamplerate;
static jmethodID mid_ar_read;

static jfieldID fid_out;
static jfieldID fid_st_freq;
static jfieldID fid_st_freq_changed;
static jfieldID fid_st_width;
static jfieldID fid_st_height;
static jfieldID fid_st_surface_changed;
static jfieldID fid_st_recorder;

static jintArray bitmap;


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
	CLASS(strobe, "org/galexander/tunertime/Strobe")
	CLASS(audiorecord, "android/media/AudioRecord")

	METHOD(println, printstream, "println", "(Ljava/lang/String;)V")
	METHOD(st_do_draw, strobe, "do_draw", "([III)I")
	METHOD(st_isinterrupted, strobe, "isInterrupted", "()Z")
	METHOD(ar_getsamplerate, audiorecord, "getSampleRate", "()I")
	METHOD(ar_read, audiorecord, "read", "([SII)I")

	STFIELD(out, system, "out", "Ljava/io/PrintStream;")
	FIELD(st_freq, strobe, "freq", "D")
	FIELD(st_freq_changed, strobe, "freq_changed", "Z")
	FIELD(st_width, strobe, "width", "I")
	FIELD(st_height, strobe, "height", "I")
	FIELD(st_surface_changed, strobe, "surface_changed", "Z")
	FIELD(st_recorder, strobe, "recorder", "Landroid/media/AudioRecord;")

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

/* This is the entry point from the Java code...it is a loop that checks for
 * thread state changes, reads audio, then writes to screen. */
JNIEXPORT void JNICALL
Java_org_galexander_tunertime_Strobe_runner(JNIEnv *env_, jobject this)
{
	jshortArray readbuf;

	if (!jni_init(env_)) {
		return;
	}
/* XXX - make it use the getMinBufferSize() value for SAMPS_PER_READ, and make it use at least 2*that for the buffer size */

println("into JNI");
	jobject recorder = (*env)->GetObjectField(env, this, fid_st_recorder);
	sample_rate = (*env)->CallIntMethod(env, recorder, mid_ar_getsamplerate);

	readbuf = (*env)->NewShortArray(env, SAMPS_PER_READ);
	
	while (1) {
		int num_read, to_read;

		/* first, check for any important state changes */
		if ((*env)->GetBooleanField(env, this, fid_st_freq_changed)) {
			(*env)->SetBooleanField(env, this, fid_st_freq_changed, JNI_FALSE);
			base_freq = (*env)->GetDoubleField(env, this, fid_st_freq);
			recalculate();
		}
		if ((*env)->GetBooleanField(env, this, fid_st_surface_changed)) {
			(*env)->SetBooleanField(env, this, fid_st_surface_changed, JNI_FALSE);
			width = (*env)->GetIntField(env, this, fid_st_width);
			height = (*env)->GetIntField(env, this, fid_st_height);
			recalculate();
			resize_bitmap();
		}
		if ((*env)->CallBooleanMethod(env, this, mid_st_isinterrupted)) {
			/* thread was interrupted, must be time for it to die */
println("thread interrupted");
			return;
		}

		/* then get sound */
		to_read = SND_BUFSAMPS-snd_ofs;
		if (to_read > SAMPS_PER_READ) {
			to_read = SAMPS_PER_READ;
		}
		num_read = (*env)->CallIntMethod(env, recorder, mid_ar_read, readbuf, 0, to_read);
		if (num_read <= 0) {
			return;
		}
		(*env)->GetShortArrayRegion(env, readbuf, 0, num_read, snd_buf+snd_ofs);
		snd_ofs = (snd_ofs+num_read)%SND_BUFSAMPS;

		/* process sound into bitmap */
		bitmap_ptr = (*env)->GetPrimitiveArrayCritical(env, bitmap, 0);
		newdata();
		(*env)->ReleasePrimitiveArrayCritical(env, bitmap, bitmap_ptr, 0);
println("filter=%d",(int)strobes[0].filtered[0]);
		/* send bitmap to surface */
		(*env)->CallBooleanMethod(env, this, mid_st_do_draw, bitmap, width, height);
	}
}
