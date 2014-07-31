package org.galexander.tunertime;

import java.lang.*;
import android.view.*;
import android.graphics.*;
import android.media.*;

public class Strobe extends Thread {
	private volatile int cents;
	private volatile int harm1_mult, harm2_mult;
	private volatile boolean cents_changed = false;
	private volatile int width = 0, height = 0;
	private volatile boolean surface_changed = false;
	private SurfaceHolder holder = null;

	private AudioRecord recorder = null;
	private int record_bufsz = 0;
	private TunerTime ui = null;

	Strobe(TunerTime ui_) {
		ui = ui_;
	}

	public void setCents(int cents_, int harm1_mult_, int harm2_mult_) {
		cents = cents_;
		harm1_mult = harm1_mult_;
		harm2_mult = harm2_mult_;
		cents_changed = true;
	}

	public void setSurface(SurfaceHolder holder_, int width_, int height_) {
		holder = holder_;
		width = width_;
		height = height_;
		surface_changed = true;
	}

	private native void runner();		/* efficient C loop */

	private Paint label_p = null;
	private String[] labels = null;

	private void draw_labels(Canvas c) {
		int y = 0;
		int strobe_height = height/labels.length;

		if (label_p == null) {
			label_p = new Paint();
			label_p.setARGB(0xff,0xaa,0xaa,0xff);
			label_p.setTextAlign(Paint.Align.LEFT);
			label_p.setTextSize(50);
			label_p.setTypeface(Typeface.create(Typeface.MONOSPACE, Typeface.BOLD));
			label_p.setFakeBoldText(true);
			label_p.setAntiAlias(true);
			label_p.setSubpixelText(true);
		}
		if (labels == null) {
			return;
		}
		for (int i = 0; i < labels.length; i++) {
			y += strobe_height;
			c.drawText(labels[i], 0, y, label_p);
		}
	}

	/* Called from JNI to handle all of the Canvas interactions to draw the
	 * bitmap.  Returns true iff the thread is interrupted and should exit. */
	private jboolean do_draw(int[] bitmap, int w, int h) {
		if (holder == null) {
			return false;
		}
		Canvas c = holder.lockCanvas();
		if (c == null) {
			return false;
		}
		if (interrupted()) {
			holder.unlockCanvasAndPost(c);
			return true;
		}
		try {
			if ((c.getWidth() == w) && (c.getHeight() == h)) {
				c.drawBitmap(bitmap, /*offset=*/0, /*stride=*/w,
					/*x=*/0, /*y=*/0, /*wid=*/w, /*hght=*/h,
					/*hasAlpha=*/false, /*paint=*/null);
				draw_labels(c);
			}
		} finally {
			holder.unlockCanvasAndPost(c);
		}
		return false;
	}

	public void run() {
		try {
			if (!sound_start()) {
				return;
			}
			runner();
		} finally {
			sound_stop();
		}
	}

	public boolean sound_start() {
		if (recorder != null) {
			sound_stop();
		}
		record_bufsz = AudioRecord.getMinBufferSize(
				44100, AudioFormat.CHANNEL_IN_MONO,
				AudioFormat.ENCODING_PCM_16BIT);
		recorder = new AudioRecord(MediaRecorder.AudioSource.MIC,
				44100, AudioFormat.CHANNEL_IN_MONO,
				AudioFormat.ENCODING_PCM_16BIT,
				record_bufsz*4);
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
		try {
			recorder.stop();
			recorder.release();
		} catch (IllegalStateException e) { /* whatever */ }
		recorder = null;
	}

	static {
		System.loadLibrary("strobe-jni");
	}
}
