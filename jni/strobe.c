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
static int fft_width = 1024;		/* size of freq window we are viewing */
static int fft_offset = 0;		/* offset of .., out of 1024 */
static int fft_y = 0;

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

static void
do_fft_display(void)
{
	int tot_bins = fft_size/2;
	int first_bin = ((fft_offset*tot_bins)/1024);
	int num_bins = ((fft_width*tot_bins)/1024);
	int i;
	int y1 = fft_y;
	int yn = (height*fft_size) / (10*sample_rate);
	int incr;
	double avg = 0.;
	double scale;
/* XXX - fudge height here to account for piano-bar legend? */

	if (y1 + yn > height) yn = height - y1;

	if (first_bin < 0) first_bin = 0;
	if (num_bins > tot_bins) num_bins = tot_bins;
	if (first_bin + num_bins > tot_bins) first_bin = tot_bins - num_bins;
	incr = num_bins / width;
	if (incr < 1) incr = 1;

		/* normalize the data, and compute the average */
	for (i = 0; i < num_bins; i++) {
		int n = i+first_bin;
		double t = fabs(fft_r[n]) + fabs(fft_i[n]);
		fft_r[n] = t;
		avg += t;
	}
	avg /= (((double)num_bins) / ((double)incr));
	scale = 64./avg;

	for (i = 0; i < num_bins; i+=incr) {
		int x1 = (i*width)/num_bins;
		int xn = width/num_bins;
		double val = 0;
		unsigned color;
		int j;
		for (j = 0; j < incr; j++) {
			val += fft_r[i+j+first_bin];
		}
		if (xn < 1) xn = 1;
		color = val * scale;
		if (color > 255) color = 255;
		color |= color << 8;
		filled_rect(x1, x1+xn, y1, y1+yn, color);
	}

	fft_y += yn;
	if (fft_y >= height) {
		fft_y = 0;
	}
	
	/* XXX - need to calibrate magnitude somehow */
	/* XXX - would be nice to add a row, but realistically we should just draw the single fft state to the screen */
	/* XXX - and maybe draw the legend, at least a piano keyboard? */
	/* XXX - maybe update some shared info to let Strobe::do_draw() draw text labels on the legend? */
}

static int
fft_freq2ind(double freq)
{
	if ((int)freq >= sample_rate/2) {
		return fft_size/2;
	}
	return ((double)fft_size)*(freq/((double)sample_rate));
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
	if (fft_mode) {
		for (i = nf_curr_samp; i != snd_ofs; i = (i+1)%SND_BUFSAMPS) {
			fft_r[nf_curr_ridx++] = ((double)snd_buf[i]) / 32768.0;
			if (nf_curr_ridx == fft_size) {
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



static JNIEnv *env;

static jclass cl_system;
static jclass cl_printstream;
static jclass cl_string;
static jclass cl_strobe;
static jclass cl_tunertime;
static jclass cl_audiorecord;

static jmethodID mid_println;
static jmethodID mid_st_do_draw;
static jmethodID mid_st_is_interrupted;
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
	CLASS(string, "java/lang/String")
	CLASS(strobe, "org/galexander/tunertime/Strobe")
	CLASS(tunertime, "org/galexander/tunertime/TunerTime")
	CLASS(audiorecord, "android/media/AudioRecord")

	METHOD(println, printstream, "println", "(Ljava/lang/String;)V")
	METHOD(st_do_draw, strobe, "do_draw", "([III)Z")
	METHOD(st_is_interrupted, strobe, "isInterrupted", "()Z")
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
	static double old_a5 = 0.0;	/* roughly, 880 */
	double a5;

	ui = (*env)->GetObjectField(env, this, fid_st_ui);

	for (i = 0; i < NUM_STROBES; i++) {
		int mult = ((i==0) ? 1 : ((i==1) ? harm1_mult : harm2_mult));
		strobes[i].y = y;
		strobes[i].height = sheight;
		strobes[i].freq = (*env)->CallDoubleMethod(env, ui, mid_tt_cents_to_freq, (jint)cents, (jint)mult);
		strobe_recalc(&strobes[i]);

		y += sheight;
	}

	a5 = (*env)->CallDoubleMethod(env, ui, mid_tt_cents_to_freq, (jint)6000, (jint)1);
	if (old_a5 != a5) {
		/* recalculate note filters too */
                /* XXX - do i need to know a5 change now?  maybe to redraw the legend on the bottom of the screen? */
/* 
		int last_fft, curr_fft;
		last_fft = fft_freq2ind((*env)->CallDoubleMethod(env, ui, mid_tt_cents_to_freq, (jint)(NOTES_LOWEST-1)*100, (jint)1));
		for (i = 0; i < NOTES_NUM; i++) {
			struct note_filter_rec *nf = &notefilts[i];
			nf->freq = (*env)->CallDoubleMethod(env, ui, mid_tt_cents_to_freq, (jint)(i+NOTES_LOWEST)*100, (jint)1);
			curr_fft = fft_freq2ind(nf->freq);
			nf->fft_bin_lo = (last_fft + curr_fft) / 2;
			if (i) notefilts[i-1].fft_bin_hi = nf->fft_bin_lo-1;
			nf->output = 0.0;
			last_fft = curr_fft;
		}
		notefilts[i-1].fft_bin_hi = fft_freq2ind((*env)->CallDoubleMethod(env, ui, mid_tt_cents_to_freq, (jint)(i+NOTES_LOWEST)*100, (jint)1));
*/
		old_a5 = a5;
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
fft_mode_changed(void) {
	memset(bitmap_ptr, 0, width*height*sizeof *bitmap_ptr);
	if (fft_mode) {
		fft_y = 0;
	} else {
		int i;
		for (i = 0; i < NUM_STROBES; i++) {
			strobes[i].prev_pos = 0;
		}
	}
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
		if ((*env)->GetBooleanField(env, this, fid_st_fft_changed)) {
			jboolean fft_mode_ = (*env)->GetBooleanField(env, this,
							fid_st_fft_mode);
			if (fft_mode != fft_mode_) {
				fft_mode = fft_mode_;
				fft_mode_changed();
			}
			fft_size = (*env)->GetIntField(env, this, fid_st_fft_size);
			fft_width = (*env)->GetIntField(env, this, fid_st_fft_width);
			fft_offset = (*env)->GetIntField(env, this, fid_st_fft_offset);
		}

		if ((*env)->CallBooleanMethod(env, this, mid_st_is_interrupted)) {
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
			newdata();
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
