package org.galexander.tunertime;

import java.lang.*;
import android.view.*;
import android.graphics.*;
import android.media.*;

public class Strobe extends Thread {
	private volatile double freq;
	private volatile boolean freq_changed = false;
	private volatile int width = 0, height = 0;
	private volatile boolean surface_changed = false;
	private SurfaceHolder holder = null;

	private AudioRecord recorder = null;

	private final static int SAMPS_PER_READ = 2048;
	
	public void setFreq(double freq_) {
		if (freq == freq_) {
			return;
		}
		freq = freq_;
		freq_changed = true;
	}

	public void setSurface(SurfaceHolder holder_, int width_, int height_) {
		holder = holder_;
		width = width_;
		height = height_;
		surface_changed = true;
	}

	private native void runner();		/* efficient C loop */

	/* Called from JNI to handle all of the Canvas interactions to draw the
	 * bitmap.  Returns 1 for success, 0 for failure (which means that the
	 * underlying surface has probably re-sized) */
	private int do_draw(int[] bitmap, int w, int h) {
		Canvas c = null;
		try {
			c = holder.lockCanvas();

			/* supposedly lockCanvas() prevents the surface from
			 * changing underneath us, so trust that and this is the
			 * appropriate place for a consistency check */
			if ((w != width) || (h != height)) {
				return 0;
			}
			c.drawBitmap(bitmap, /*offset=*/0, /*stride=*/w,
					/*x=*/0, /*y=*/0, /*wid=*/w, /*hght=*/h,
					/*hasAlpha=*/false, /*paint=*/null);
		} finally {
			holder.unlockCanvasAndPost(c);
		}
		return 1;
	}

	public void run() {
System.out.println("strobe start");
		if (!sound_start()) {
			return;
		}
		runner();
		sound_stop();
System.out.println("strobe stop");
}

	public boolean sound_start() {
		if (recorder != null) {
			sound_stop();
		}
		int min_bufsz = AudioRecord.getMinBufferSize(44100,
				AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
			/* 2 bytes per sample, 2 read-worths per buffer */
		int wanted_bufsz = SAMPS_PER_READ*2*2;

		if (wanted_bufsz < min_bufsz) {
			wanted_bufsz = min_bufsz;
		}

		recorder = new AudioRecord(MediaRecorder.AudioSource.MIC,
				44100, AudioFormat.CHANNEL_IN_MONO,
				AudioFormat.ENCODING_PCM_16BIT,
				wanted_bufsz);
		if ((recorder.getAudioFormat() !=
					AudioFormat.ENCODING_PCM_16BIT) ||
		    (recorder.getChannelCount() != 1)) {
			System.out.println("got funny format/channels back from AudioRecord!");
			sound_stop();
			return false;
		}
		try {
			recorder.startRecording();
		} catch (IllegalStateException e) {
			System.out.println("illegal state for startRecording()");
			sound_stop();
			return false;
		}
		return true;
	}

	private void sound_stop() {
		if (recorder == null) {
			return;
		}
		recorder.stop();
		recorder.release();
		recorder = null;
	}
}
