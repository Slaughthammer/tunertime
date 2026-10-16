package org.galexander.tunertime;

import android.view.View;
import android.widget.Button;
import android.widget.TextView;
import java.util.Arrays;

/* length mode (organ mode, in place of FFT): how much the sounding pipe is
 * too long or too short for the target note.
 *
 * A flue pipe sounds at f = c/(2*(L+e)) when open, c/(4*(L+e)) when
 * stopped, with L the speaking length and e the end correction, which
 * depends on the diameter but not on L.  So the length to cut or add is
 *	dL = c/(n*f_actual) - c/(n*f_target),   n = 2 open, 4 stopped
 * whatever e is; positive means too long (flat).  c follows the
 * temperature, c = 331.3*sqrt(1+T/273.15) m/s; a degree off moves dL by
 * less than 0.2%.  The percentage is relative to the acoustic length
 * c/(n*f_target), i.e. f_target/f_actual - 1.
 *
 * The estimate is the median of the detector's pitches over the last
 * WINDOW ms that had sound, held after the pipe stops; the spread (median
 * absolute deviation) tells whether it has settled. */
public class pipelength {
	private static final long WINDOW = 1000;	/* ms */
	private static final int MAX_EST = 64;
	private static final double STABLE_CENTS = 1.0;
	private static final int MIN_EST = 5;

	private final TunerTime ui;
	private final TextView main_display, detail_display, temp_display;
	private final Button kind_button;

	private final double[] hz = new double[MAX_EST];
	private final long[] when = new long[MAX_EST];
	private int n_est = 0;
	private int last_target = -1;
	private double last_focus = 0.0d;

	pipelength(TunerTime ui_, View panel) {
		ui = ui_;
		main_display = (TextView)panel.findViewById(R.id.length_display);
		detail_display = (TextView)panel.findViewById(
				R.id.length_detail_display);
		temp_display = (TextView)panel.findViewById(R.id.temp_display);
		kind_button = (Button)panel.findViewById(R.id.pipe_kind_button);
		kind_button.setOnClickListener(new View.OnClickListener() {
			public void onClick(View v) {
				parms.pipe_stopped = !parms.pipe_stopped;
				update();
			} });
		((Button)panel.findViewById(R.id.temp_plus)).setOnClickListener(
				new View.OnClickListener() {
			public void onClick(View v) { incr_temp(1); } });
		((Button)panel.findViewById(R.id.temp_minus)).setOnClickListener(
				new View.OnClickListener() {
			public void onClick(View v) { incr_temp(-1); } });
	}

	private void incr_temp(int d) {
		parms.temperature += d;
		update();
	}

	public void clear() {
		n_est = 0;
	}

	/* one estimate from the detector; hz <= 0 is silence */
	public void add(double f) {
		if (f > 0.0) {
			if (n_est == MAX_EST) {
				System.arraycopy(hz, 1, hz, 0, MAX_EST-1);
				System.arraycopy(when, 1, when, 0, MAX_EST-1);
				n_est--;
			}
			hz[n_est] = f;
			when[n_est] = System.currentTimeMillis();
			n_est++;
		}
		update();
	}

	public static double speed_of_sound(double celsius) {
		return 331.3d * Math.sqrt(1.0d + celsius/273.15d);
	}

	private static double median(double[] v, int n) {
		Arrays.sort(v, 0, n);
		return ((n%2) == 1) ? v[n/2] : 0.5d*(v[n/2-1]+v[n/2]);
	}

	private static String fmt(String f, double v) {
		return String.format(java.util.Locale.US, f, v);
	}

	public void update() {
		parms.temperature = Math.max(-20, Math.min(45,
						parms.temperature));
		temp_display.setText(parms.temperature + "\u00b0C");
		kind_button.setText(parms.pipe_stopped ? "stopped" : "open");

		if ((parms.strobe_cents != last_target) ||
		    (parms.focus_ratio != last_focus)) {
			/* a new note or rank: earlier pitches belong to
			 * another pipe */
			last_target = parms.strobe_cents;
			last_focus = parms.focus_ratio;
			n_est = 0;
		}
		/* the note's pipe; a focused partial (4', 2 2/3', ...)
		 * stands for its own rank */
		double f_base = ui.cents_to_freq(parms.strobe_cents, 1);
		if (parms.focus_ratio > 0.0d) {
			f_base *= parms.focus_ratio;
		}

		/* pitches in the WINDOW up to the latest, as cents off f_base */
		double[] c = new double[MAX_EST];
		int n = 0;
		for (int i = n_est-1; i >= 0; i--) {
			if (when[n_est-1] - when[i] > WINDOW) {
				break;
			}
			c[n++] = 1200.0d * Math.log(hz[i]/f_base) / Math.log(2.0d);
		}
		if (n == 0) {
			main_display.setText("listening...");
			main_display.setTextColor(color(R.color.text_muted));
			detail_display.setText(fmt("target %.2f Hz", f_base));
			return;
		}
		double med = median(c, n);
		double[] dev = new double[n];
		for (int i = 0; i < n; i++) {
			dev[i] = Math.abs(c[i]-med);
		}
		double spread = median(dev, n);

		/* a pipe is not an octave out: that is the detector or
		 * another rank, so compare with the nearest octave */
		int oct = (int)Math.round(med/1200.0d);
		double cents = med - 1200.0d*oct;
		double f_ref = f_base * Math.pow(2.0d, oct);
		double f_act = f_ref * Math.pow(2.0d, cents/1200.0d);

		double div = parms.pipe_stopped ? 4.0d : 2.0d;
		double cs = speed_of_sound(parms.temperature);
		double len_ref = 1000.0d * cs/(div*f_ref);	/* mm */
		double mm = 1000.0d * cs/(div*f_act) - len_ref;
		double pct = 100.0d * (f_ref/f_act - 1.0d);

		String amm = fmt((Math.abs(mm) >= 100.0d) ? "%.0f" : "%.1f",
				Math.abs(mm));
		String s;
		if (amm.equals("0.0") || amm.equals("0")) {
			s = "0.0 mm: in tune";
		} else {
			s = amm + " mm " + ((mm > 0.0d) ? "too long" : "too short");
		}
		boolean recent = (System.currentTimeMillis() - when[n_est-1] <
					WINDOW);
		boolean stable = (n >= MIN_EST) && (spread < STABLE_CENTS);
		if (!recent) {
			s += " (held)";
		}
		main_display.setText(s);
		main_display.setTextColor(color(!recent ? R.color.text_muted :
				stable ? R.color.accent : R.color.warn));
		detail_display.setText(fmt("%+.2f %%", pct) +
				fmt("  %+.1f ct", cents) +
				fmt("  \u00b1%.1f ct", spread) +
				fmt("  L\u2248%.0f mm", len_ref) +
				((oct != 0) ? fmt("  (%.0f Hz)", f_ref) : ""));
	}

	private int color(int id) {
		return ui.getResources().getColor(id);
	}
}
