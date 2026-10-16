/* record.c - keeps the last few seconds of microphone input, so that the
 * Java side (DebugRecorder) can save what the detector heard when the user
 * had to correct it.  Fed from the strobe thread with every buffer read;
 * DebugRecorder.grab() copies it out on another thread, hence the lock. */
#include <pthread.h>
#include <string.h>
#include <jni.h>
#include "interface.h"

#define REC_SECS	8
#define REC_MAX		(48000*REC_SECS)

static short rec[REC_MAX];
static int rec_len;		/* capacity in use, REC_SECS of sample_rate */
static int rec_pos;		/* next write */
static int rec_filled;
static pthread_mutex_t rec_lock = PTHREAD_MUTEX_INITIALIZER;

void
record_init(int rate)
{
	pthread_mutex_lock(&rec_lock);
	rec_len = rate*REC_SECS;
	if ((rec_len <= 0) || (rec_len > REC_MAX)) rec_len = REC_MAX;
	rec_pos = rec_filled = 0;
	pthread_mutex_unlock(&rec_lock);
}

void
record_feed(const short *s, int n)
{
	pthread_mutex_lock(&rec_lock);
	while ((n > 0) && (rec_len > 0)) {
		int k = rec_len - rec_pos;
		if (k > n) k = n;
		memcpy(rec+rec_pos, s, k*sizeof(short));
		rec_pos = (rec_pos+k) % rec_len;
		rec_filled += k;
		if (rec_filled > rec_len) rec_filled = rec_len;
		s += k;
		n -= k;
	}
	pthread_mutex_unlock(&rec_lock);
}

/* copies the most recent samples, oldest first, into dst; returns how
 * many */
JNIEXPORT jint JNICALL
Java_org_galexander_tunertime_DebugRecorder_grab(JNIEnv *env, jclass cl,
		jshortArray dst)
{
	jint n = (*env)->GetArrayLength(env, dst);
	pthread_mutex_lock(&rec_lock);
	if (n > rec_filled) n = rec_filled;
	int start = (rec_pos - n + rec_len) % ((rec_len > 0) ? rec_len : 1);
	int first = rec_len - start;
	if (first > n) first = n;
	if (n > 0) {
		(*env)->SetShortArrayRegion(env, dst, 0, first, rec+start);
		if (n > first) {
			(*env)->SetShortArrayRegion(env, dst, first, n-first,
						rec);
		}
	}
	pthread_mutex_unlock(&rec_lock);
	return n;
}
