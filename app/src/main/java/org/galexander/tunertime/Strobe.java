package org.galexander.tunertime;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.view.SurfaceHolder;
import java.util.concurrent.Semaphore;

public class Strobe extends Thread {
	public final static int FFT_RANGE_MULT = 65536;

	private static final Object lock = new Object();
	private static volatile Strobe curr_thread = null;
	private static final Semaphore sem = new Semaphore(1);

	private volatile int width = 0, height = 0;
	private SurfaceHolder holder = null;
	private volatile boolean surface_changed = false;

	public volatile boolean paused = false;

	private TunerTime ui = null;

	Strobe(TunerTime ui_) {
		super();
		synchronized(lock) {
			if (curr_thread != null) {
				curr_thread.interrupt();
			}
			curr_thread = this;
		}
		ui = ui_;
	}

	public void setSurface(SurfaceHolder holder_, int width_, int height_) {
		synchronized(lock) {
			holder = holder_;
			width = width_;
			height = height_;
			surface_changed = true;
		}
	}

	/* if the surface has changed, return [width, height].  else, null */
	public int[] check_surface() {
		int[] ret = null;
		synchronized(lock) {
			if (surface_changed) {
				ret = new int[2];
				ret[0] = width;
				ret[1] = height;
				surface_changed = false;
			}
		}
		return ret;
	}

	public void toggle_pause() {
		paused = !paused;
		if (!paused) {
			parms.fft_xoffset = FFT_RANGE_MULT;
		}
	}

	private native void runner();		/* efficient C loop */

	private Paint label_p = null;
	private Paint pause_p = null;
	private Paint pianoname_p = null;
	private String[] labels = null;
	private String pianoname = null;

	private void draw_labels(Canvas c) {
		if ((labels == null) || (labels.length == 0) || parms.fft_mode) {
			return;
		}

		int strobe_height = height/labels.length;
		if (label_p == null) {
			label_p = new Paint();
			label_p.setARGB(0xff,0xff,0xff,0xff);
			label_p.setTextAlign(Paint.Align.LEFT);
			label_p.setTextSize(strobe_height/6);
			label_p.setTypeface(Typeface.create(Typeface.MONOSPACE, Typeface.BOLD));
			label_p.setFakeBoldText(true);
			label_p.setAntiAlias(true);
			label_p.setSubpixelText(true);
		}
		int y = -(int)label_p.getFontMetrics().ascent;
		for (int i = 0; i < labels.length; i++) {
			c.drawText(labels[i], 0, y, label_p);
			y += strobe_height;
		}
	}

	private void draw_paused(Canvas c) {
		if (pause_p == null) {
			pause_p = new Paint();
			pause_p.setARGB(0xff,0xff,0xff,0xff);
			pause_p.setTextAlign(Paint.Align.LEFT);
			pause_p.setTextSize(height/10);
			pause_p.setTypeface(Typeface.create(Typeface.MONOSPACE, Typeface.BOLD));
			pause_p.setFakeBoldText(true);
			pause_p.setAntiAlias(true);
			pause_p.setSubpixelText(true);
		}
		c.drawText("paused", 0, height/2, pause_p);
	}

	/* like "A49" for A-4 */
	private void draw_pianoname(Canvas c) {
		if (parms.fft_mode || (pianoname == null)) {
			return;
		}
		if (pianoname_p == null) {
			pianoname_p = new Paint();
			pianoname_p.setARGB(0xff,0xff,0xff,0xff);
			pianoname_p.setTextAlign(Paint.Align.RIGHT);
			pianoname_p.setTextSize(height/24);
			pianoname_p.setTypeface(Typeface.create(Typeface.MONOSPACE, Typeface.BOLD));
			pianoname_p.setFakeBoldText(true);
			pianoname_p.setAntiAlias(true);
			pianoname_p.setSubpixelText(true);
		}
		int y = -(int)label_p.getFontMetrics().ascent;
		c.drawText(pianoname, width-1, y, pianoname_p);
	}

	private void draw_all(Canvas c, int[] bitmap, int w, int h) {
		if ((c.getWidth() == w) && (c.getHeight() == h)) {
			c.drawBitmap(bitmap, /*offset=*/0, /*stride=*/w,
				/*x=*/0, /*y=*/0, /*wid=*/w, /*hght=*/h,
				/*hasAlpha=*/true, /*paint=*/null);
			draw_labels(c);
			if (paused) {
				draw_paused(c);
			}
			draw_pianoname(c);
		}
	}

	/* Called from JNI to handle all of the Canvas interactions to draw the
	 * bitmap.  Returns true iff the thread is interrupted and should exit.
	 */
	private boolean do_draw(int[] bitmap, int w, int h) {
		if (time_to_die()) {
			return true;
		}
		synchronized(lock) {
			if (holder != null) {
				Canvas c = holder.lockCanvas();
				if (c != null) {
					try {
						draw_all(c, bitmap, w, h);
					} finally {
						holder.unlockCanvasAndPost(c);
					}
				}
			}
		}
		return false;
	}

	public void run() {
		try {
			sem.acquire();
		} catch (Exception e) {
			synchronized(lock) {
				if (curr_thread == this) {
					curr_thread = null;
				}
			}
			return;
		}
		try {
			Listener.start_recording();
			runner();
		} finally {
			Listener.stop_recording();
			synchronized(lock) {
				if (curr_thread == this) {
					curr_thread = null;
				}
			}
			sem.release();
		}
	}

	private boolean time_to_die() {
		if (isInterrupted()) {
			return true;
		}
		synchronized(lock) {
			if (curr_thread != this) {
				/* a new thread has been started, we'd better
				 * die toot sweet! */
				return true;
			}
		}
		return false;
	}

	static {
		System.loadLibrary("strobe-jni");
	}
}
