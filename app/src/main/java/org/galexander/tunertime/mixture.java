package org.galexander.tunertime;

/* mixture mode (organ mode): no target pitch is assumed.  the focused
 * partial's pitch is averaged over MEASURE_SECONDS (MEASURE_SECONDS_LOW
 * on a row below 8'), and the target moves there, so the strobes stand
 * still.
 *
 * the measurement is the drift of the focused strobe row (jni/strobe.c):
 * how many cycles the partial gains on the row frequency, counted only
 * while the row hears enough signal.  cycles over seconds is then the
 * average offset in Hz, exactly what makes the strobe pattern move.
 * fed from the strobe thread, read from the UI thread */
public class mixture {
		/* seconds of usable signal to average over */
	public static final double MEASURE_SECONDS = 2.0d;
		/* ... when the row measured on is below 8' (focus ratio < 1:
		 * 16', 10 2/3', 32'), whose partial makes few cycles in 2 s
		 * and whose drift scatters more.  only usable seconds count,
		 * so the settling after an onset (jni/strobe.c) just takes
		 * longer, there is no timeout */
	public static final double MEASURE_SECONDS_LOW = 3.0d;
		/* after a change of microphone, usable seconds ignored: the
		 * drift blocks (jni/strobe.c) that straddle the change */
	private static final double SWITCH_SKIP = 0.25d;

		/* a measurement has begun to collect.  a change of
		 * microphone jumps the phase, so MicSelect holds its switches
		 * meanwhile.  not before: while nothing is heard yet a switch
		 * costs nothing, and the mic in use may be the one that
		 * hears nothing */
	public static volatile boolean busy = false;

	private boolean active = false;
	private double freq = 0.0d;		/* row frequency measured on */
	private double cycles = 0.0d, secs = 0.0d;
	private int gen = 0;			/* Listener.buffer_gen() */
	private double skip = 0.0d;
	private double need = MEASURE_SECONDS;	/* for the focused row */

		/* the usable seconds a measurement on the row of focus
		 * ratio r takes */
	public static double measure_seconds(double r) {
		return ((r > 0.0d) && (r < 1.0d - 1e-6)) ? MEASURE_SECONDS_LOW
							 : MEASURE_SECONDS;
	}

	public synchronized void start() {
		active = true;
		busy = false;
		freq = 0.0d;
		cycles = 0.0d;
		secs = 0.0d;
		gen = Listener.buffer_gen();
		skip = 0.0d;
		need = measure_seconds(parms.focus_ratio);
	}

	public synchronized void stop() {
		active = false;
		busy = false;
	}

	public synchronized boolean measuring() {
		return active;
	}

		/* 0..1 */
	public synchronized double progress() {
		return Math.min(secs / need, 1.0d);
	}

		/* one buffer's drift of the focused row (Strobe.focus_drift).
		 * returns the partial's offset in cents once measure_seconds()
		 * are in, which ends the measurement; NaN until then */
	public synchronized double feed(double freq_, double cycles_,
					double secs_) {
		if (!active || (freq_ <= 0.0d)) {
			return Double.NaN;
		}
		int g = Listener.buffer_gen();
		if (g != gen) {
			/* the microphone changed anyway (it can't always
			 * wait): start over, past the jump */
			gen = g;
			freq = freq_;
			cycles = 0.0d;
			secs = 0.0d;
			skip = SWITCH_SKIP;
			busy = false;
			return Double.NaN;
		}
		if (skip > 0.0d) {
			skip -= secs_;
			return Double.NaN;
		}
		if (freq_ != freq) {
			/* other row, or the target moved: start over */
			freq = freq_;
			cycles = 0.0d;
			secs = 0.0d;
			need = measure_seconds(parms.focus_ratio);
		}
		cycles += cycles_;
		secs += secs_;
		busy = (secs > 0.0d);
		if (secs < need) {
			return Double.NaN;
		}
		active = false;
		busy = false;
		return 1200.0d * Math.log((freq + cycles/secs)/freq) /
				Math.log(2.0d);
	}
}
