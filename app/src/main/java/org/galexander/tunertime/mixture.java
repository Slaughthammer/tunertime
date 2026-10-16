package org.galexander.tunertime;

/* mixture mode (organ mode): no target pitch is assumed.  the focused
 * partial's pitch is averaged over MEASURE_SECONDS, and the target moves
 * there, so the strobes stand still.
 *
 * the measurement is the drift of the focused strobe row (jni/strobe.c):
 * how many cycles the partial gains on the row frequency, counted only
 * while the row hears enough signal.  cycles over seconds is then the
 * average offset in Hz, exactly what makes the strobe pattern move.
 * fed from the strobe thread, read from the UI thread */
public class mixture {
		/* seconds of usable signal to average over */
	public static final double MEASURE_SECONDS = 2.0d;

	private boolean active = false;
	private double freq = 0.0d;		/* row frequency measured on */
	private double cycles = 0.0d, secs = 0.0d;

	public synchronized void start() {
		active = true;
		freq = 0.0d;
		cycles = 0.0d;
		secs = 0.0d;
	}

	public synchronized void stop() {
		active = false;
	}

	public synchronized boolean measuring() {
		return active;
	}

		/* 0..1 */
	public synchronized double progress() {
		return Math.min(secs / MEASURE_SECONDS, 1.0d);
	}

		/* one buffer's drift of the focused row (Strobe.focus_drift).
		 * returns the partial's offset in cents once MEASURE_SECONDS
		 * are in, which ends the measurement; NaN until then */
	public synchronized double feed(double freq_, double cycles_,
					double secs_) {
		if (!active || (freq_ <= 0.0d)) {
			return Double.NaN;
		}
		if (freq_ != freq) {
			/* other row, or the target moved: start over */
			freq = freq_;
			cycles = 0.0d;
			secs = 0.0d;
		}
		cycles += cycles_;
		secs += secs_;
		if (secs < MEASURE_SECONDS) {
			return Double.NaN;
		}
		active = false;
		return 1200.0d * Math.log((freq + cycles/secs)/freq) /
				Math.log(2.0d);
	}
}
