package org.galexander.tunertime;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.util.TypedValue;
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

	/* called from the native loop with each new pitch estimate */
	public void pitch_detected(double hz) {
		ui.pitch_detected(hz);
	}

	/* organ mode: the focused row's partial gained cycles on the row's
	 * frequency freq during secs of usable signal, in the last buffer of
	 * buf_secs.  called from the native loop after every buffer while a
	 * row is focused.  smoothed here for the display in place of the
	 * piano key name, and passed on for mixture mode */
	private static final double FOCUS_TAU = 0.5;	/* display, seconds */
	private double focus_freq = 0.0d;
	private double focus_cycles = 0.0d, focus_secs = 0.0d;
	private volatile String focus_str = null;
	private volatile double focus_str_ratio = 0.0d;	/* focus_str is for */

	public void focus_drift(double freq, double cycles, double secs,
				double buf_secs) {
		if (freq != focus_freq) {
			focus_freq = freq;
			focus_cycles = 0.0d;
			focus_secs = 0.0d;
		}
		double d = Math.exp(-buf_secs/FOCUS_TAU);
		focus_cycles = focus_cycles*d + cycles;
		focus_secs = focus_secs*d + secs;
		String s = parms.footage_str(parms.focus_ratio) + " ";
		if ((focus_secs > FOCUS_TAU/4.0d) && (freq > 0.0d)) {
			double c = 1200.0d * Math.log(
				(freq + focus_cycles/focus_secs)/freq) /
				Math.log(2.0d);
			s += note.cents_str((int)Math.round(
						c*preset.UNITS_PER_CENT));
		} else {
			s += "--.-c";
		}
		focus_str = s;
		focus_str_ratio = parms.focus_ratio;
		ui.focus_drift(freq, cycles, secs);
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
	private int label_h = 0;
	private String pianoname = null;

	private void draw_labels(Canvas c) {
		if ((labels == null) || (labels.length == 0) || parms.fft_mode) {
			return;
		}

		int strobe_height = height/labels.length;
		if (label_p == null) {
			label_p = new Paint();
			label_p.setARGB(0xff,0xe8,0xea,0xed);
			label_p.setTextAlign(Paint.Align.LEFT);
			label_p.setTypeface(Typeface.create(Typeface.MONOSPACE, Typeface.BOLD));
			label_p.setFakeBoldText(true);
			label_p.setAntiAlias(true);
			label_p.setSubpixelText(true);
		}
		if (label_h != strobe_height) {
			/* a fifth of a row, but at least 12sp so that five rows
			 * still read in landscape, and never over a third */
			float sp12 = TypedValue.applyDimension(
				TypedValue.COMPLEX_UNIT_SP, 12.0f,
				ui.getResources().getDisplayMetrics());
			float sz = Math.max(strobe_height/5.0f, sp12);
			label_p.setTextSize(Math.min(sz, strobe_height/3.0f));
			label_h = strobe_height;
		}
		int focus = parms.organ_mode ? parms.focus_row() : -1;
		int y = -(int)label_p.getFontMetrics().ascent;
		for (int i = 0; i < labels.length; i++) {
			if (i == focus) {
				draw_focus(c, i*strobe_height, strobe_height);
				c.drawText(labels[i], 0, y, focus_p);
			} else {
				c.drawText(labels[i], 0, y, label_p);
			}
			y += strobe_height;
		}
	}

	/* organ mode: frames the focused row, labelled in the accent colour */
	private Paint focus_p = null;
	private Paint frame_p = null;
	private void draw_focus(Canvas c, int y, int h) {
		if (frame_p == null) {
			int accent = ui.getResources().getColor(R.color.accent);
			frame_p = new Paint();
			frame_p.setColor(accent);
			frame_p.setStyle(Paint.Style.STROKE);
			frame_p.setStrokeWidth(Math.max(3.0f, width/150.0f));
			focus_p = new Paint(label_p);
			focus_p.setColor(accent);
		}
		focus_p.setTextSize(label_p.getTextSize());
		float sw = frame_p.getStrokeWidth()/2.0f;
		c.drawRect(sw, y+sw, width-sw, y+h-sw, frame_p);
	}

	private void draw_paused(Canvas c) {
		if (pause_p == null) {
			pause_p = new Paint();
			pause_p.setARGB(0xff,0x4c,0x8d,0xff);
			pause_p.setTextAlign(Paint.Align.LEFT);
			pause_p.setTextSize(height/10);
			pause_p.setTypeface(Typeface.create(Typeface.MONOSPACE, Typeface.BOLD));
			pause_p.setFakeBoldText(true);
			pause_p.setAntiAlias(true);
			pause_p.setSubpixelText(true);
		}
		c.drawText("paused", 0, height/2, pause_p);
	}

	/* like "A49" for A-4.  in organ mode, the focused partial's offset
	 * in place of the piano key, like "4' +01.5c" */
	private void draw_pianoname(Canvas c) {
		String s = pianoname;
		if (parms.organ_mode) {
			s = ((parms.focus_ratio > 0.0d) &&
			     (focus_str_ratio == parms.focus_ratio)) ?
						focus_str : null;
		}
		if (parms.fft_mode || (s == null)) {
			return;
		}
		if (pianoname_p == null) {
			pianoname_p = new Paint();
			pianoname_p.setARGB(0xff,0x9a,0xa0,0xa6);
			pianoname_p.setTextAlign(Paint.Align.RIGHT);
			pianoname_p.setTextSize(height/24);
			pianoname_p.setTypeface(Typeface.create(Typeface.MONOSPACE, Typeface.BOLD));
			pianoname_p.setFakeBoldText(true);
			pianoname_p.setAntiAlias(true);
			pianoname_p.setSubpixelText(true);
		}
		int y = -(int)pianoname_p.getFontMetrics().ascent;
		if (label_p != null) {
			/* share the baseline of the first label */
			y = Math.max(y, -(int)label_p.getFontMetrics().ascent);
		}
		c.drawText(s, width-1, y, pianoname_p);
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
