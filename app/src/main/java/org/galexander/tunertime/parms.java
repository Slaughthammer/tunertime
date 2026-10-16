package org.galexander.tunertime;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.preference.PreferenceManager;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

public class parms {
		/* strobe target, tenths of a cent (preset.UNITS_PER_CENT),
		 * 48000 = A4.  read by the native side */
	static public volatile int strobe_cents = 48000;
		/* note-mode position before any temperament is applied */
	static public int note_cents = 48000;
		/* apply a 12-note preset as temperament in note mode */
	static public boolean note_temper = true;
		/* auto note detection is following the input (read natively) */
	static public volatile boolean auto_note = false;
	static public final int MAX_STROBES = 8;	/* matches jni/interface.h */
	static public volatile int num_strobes = 5;
		/* harmonic multiplier per strobe row, ascending */
	static public volatile int[] harm_mult = { 1, 2, 4, 6, 8, 1, 1, 1 };
	static public volatile String strobe_color = "batlow";

	static public volatile boolean fft_mode = false;
			/* number of samples in FFT window */
	static public volatile int fft_size = 8192;
			/* zoom level */
	static public volatile int fft_width = Strobe.FFT_RANGE_MULT/10;
	static public volatile int fft_height = Strobe.FFT_RANGE_MULT/4;
			/* offset in FFT_RANGE_MULTs of screen */
	static public volatile int fft_xoffset = Strobe.FFT_RANGE_MULT;
	static public volatile int fft_yoffset = Strobe.FFT_RANGE_MULT;
	static public volatile boolean fft_grid_freq = false;

	static public volatile boolean changed = false;

	static public int A_freq = 4400;              /* 440.0 *10 */
	static public int cents_per_octave = 0;       /* *10 */
		/* organ mode (Settings): no stretch, organ-specific displays */
	static public volatile boolean organ_mode = false;
		/* partial the user focused on (organ mode): its frequency
		 * divided by the note's 8' fundamental, e.g. 2.0 for 4',
		 * 0.5 for 16'.  0 = none.  pitch detection prefers it */
	static public volatile double focus_ratio = 0.0d;
		/* length mode (organ mode, instead of FFT): how much the pipe
		 * is too long or short.  keeps the detector running natively */
	static public volatile boolean length_mode = false;
	static public boolean pipe_stopped = false;	/* gedackt */
	static public int temperature = 18;		/* degrees C */

		/* stretch in effect, tenths of a cent per octave; organs are
		 * tuned without stretch */
	public static int stretch() {
		return organ_mode ? 0 : cents_per_octave;
	}

	static public boolean preset_mode = false;
	static public int preset_id = -1;
	static public int preset_note = 0;
	static public int preset_oct = 0;

	private static SharedPreferences prefs = null;

		/* offset (tenths of a cent) that the selected preset applies to
		 * the pitch class of c in note mode, if it is a temperament */
	public static int temper_offset(int c) {
		int[] t = temper_offsets();
		if (t == null) {
			return 0;
		}
		final int semi = 100*preset.UNITS_PER_CENT;
		return t[(((c+semi/2)/semi)%12+12)%12];
	}
	public static int[] temper_offsets() {
		if (!note_temper) {
			return null;
		}
		preset p = preset.find(preset_id);
		return ((p == null) ? null : p.temper_offsets());
	}


	public static void init(Activity a) {
		prefs = PreferenceManager.getDefaultSharedPreferences(a);
		check_for_legacy(a);
	}
	public static void load() {
		A_freq = prefs.getInt("A_freq", 4400);
		if (A_freq <= 880) {
			/* the setting must come from before we encoded A_freq
			 * as *10 (so there is one decimal point of info) */
			A_freq *= 10;
		}
		cents_per_octave = prefs.getInt("cents_per_octave", 0);
		if (prefs.contains("strobe_cents10")) {
			strobe_cents = prefs.getInt("strobe_cents10", 48000);
		} else {
			/* legacy: whole cents */
			strobe_cents = prefs.getInt("strobe_cents", 4800) *
					preset.UNITS_PER_CENT;
		}
		note_cents = prefs.getInt("note_cents10", strobe_cents);
		note_temper = prefs.getBoolean("note_temper", true);
		auto_note = prefs.getBoolean("auto_note", false);
		organ_mode = prefs.getBoolean("organ_mode", false);
		length_mode = prefs.getBoolean("length_mode", false);
		pipe_stopped = prefs.getBoolean("pipe_stopped", false);
		temperature = prefs.getInt("temperature", 18);
		load_harmonics();
		strobe_color = prefs.getString("strobe_color", "batlow");
		fft_mode = prefs.getBoolean("fft_mode", false);
		fft_size = prefs.getInt("fft_size", 2048);
		fft_width = prefs.getInt("fft_width", Strobe.FFT_RANGE_MULT/4);
		fft_height = prefs.getInt("fft_height", Strobe.FFT_RANGE_MULT/4);
		fft_xoffset = prefs.getInt("fft_xoffset", Strobe.FFT_RANGE_MULT);
		fft_yoffset = prefs.getInt("fft_yoffset", Strobe.FFT_RANGE_MULT);
		fft_grid_freq = prefs.getBoolean("fft_grid_freq", false);
		preset_mode = prefs.getBoolean("preset_mode", false);
		preset_id = prefs.getInt("preset_id", -1);
		preset_note = prefs.getInt("preset_note", 0);
		preset_oct = prefs.getInt("preset_oct", 0);
		preset.load(prefs);
	}

	/* "harmonics" is the set of multipliers shown, one strobe row each;
	 * older versions stored one multiplier per row as harm0..harm4 */
	private static void load_harmonics() {
		Set<String> set = prefs.getStringSet("harmonics", null);
		if (set == null) {
			set = new HashSet<String>();
			String[] defs = { "1", "2", "4", "6", "8" };
			for (int i = 0; i < defs.length; i++) {
				set.add(prefs.getString("harm"+i, defs[i]));
			}
		}
		TreeSet<Integer> sorted = new TreeSet<Integer>();
		for (String s : set) {
			try {
				int m = Integer.parseInt(s);
				if ((m >= 1) && (m <= 20)) {
					sorted.add(m);
				}
			} catch (NumberFormatException e) {
			}
		}
		if (sorted.isEmpty()) {
			sorted.add(1);
		}
		int[] mult = new int[MAX_STROBES];
		int n = 0;
		for (int m : sorted) {
			if (n >= MAX_STROBES) {
				break;
			}
			mult[n++] = m;
		}
		for (int i = n; i < MAX_STROBES; i++) {
			mult[i] = 1;
		}
		harm_mult = mult;
		num_strobes = n;
	}

	public static void save() {
		SharedPreferences.Editor editor = prefs.edit();
		editor.putInt("A_freq", A_freq);
		editor.putInt("cents_per_octave", cents_per_octave);
		editor.putInt("strobe_cents10", strobe_cents);
		editor.putInt("note_cents10", note_cents);
		editor.putBoolean("fft_mode", fft_mode);
		editor.putInt("fft_size", fft_size);
		editor.putInt("fft_width", fft_width);
		editor.putInt("fft_height", fft_height);
		editor.putInt("fft_xoffset", fft_xoffset);
		editor.putInt("fft_yoffset", fft_yoffset);
		editor.putBoolean("fft_grid_freq", fft_grid_freq);
		editor.putBoolean("preset_mode", preset_mode);
		editor.putBoolean("auto_note", auto_note);
		editor.putBoolean("length_mode", length_mode);
		editor.putBoolean("pipe_stopped", pipe_stopped);
		editor.putInt("temperature", temperature);
		editor.putInt("preset_id", preset_id);
		editor.putInt("preset_note", preset_note);
		editor.putInt("preset_oct", preset_oct);
		preset.save(editor);
		editor.commit();
	}

		/* a.getPreferences() returns an Activity-local store, while now
		 * we use the PreferenceManager default, so copy them over */
	private static void check_for_legacy(Activity a) {
		if (prefs.contains("fft_mode")) {
			return;
		}
		SharedPreferences old = a.getPreferences(Context.MODE_PRIVATE);
		if (!old.contains("fft_mode")) {
			return;
		}
		Map<String, ?> oldvals = old.getAll();
		SharedPreferences.Editor editor = prefs.edit();

		editor.clear();
		for (Map.Entry<String, ?> entry : oldvals.entrySet()) {
			String key = entry.getKey();
			Object value = entry.getValue();
			if (key.equals("harm1_mult")) {
				editor.putString("harm1", ((Integer)value).toString());
			} else if (key.equals("harm2_mult")) {
				editor.putString("harm2", ((Integer)value).toString());
			} else if (key.equals("octave_stretch")) {
				/* use older config option if available.
				 * see derivation in NOTES Aug 21, 2016 */
				int octave_stretch = ((Integer)value).intValue();
				if (octave_stretch != 0) {
					double os = ((double)octave_stretch)*0.00001d;
					double lg = Math.log(2.0d+os)/Math.log(2.0d);
					double t = (lg-1.0d)*12000.0d;
					editor.putInt("cents_per_octave", (int)Math.round(t));
				}
			} else if (key.startsWith("temper")) {
				/* ignore */
			} else if (value instanceof Integer) {
				editor.putInt(key, ((Integer)value).intValue());
			} else if (value instanceof Boolean) {
				editor.putBoolean(key, ((Boolean)value).booleanValue());
			}
		}
		oldvals = null;
		convert_temper(old, editor);
		editor.commit();

		editor = old.edit();
		editor.clear();
		editor.commit();
	}

	private static void convert_temper(SharedPreferences in,
					SharedPreferences.Editor out) {
		int[] temper = new int[12];
		boolean any_temper = false;
		for (int i = 0; i < 12; i++) {
			int v = in.getInt("temper"+i, 0);
			if (v != 0) {
				any_temper = true;
			}
			temper[i] = v;
		}
		if (!any_temper) {
			return;
		}
		String notes = ""+
			(temper[3] +3900)+","+
			(temper[4] +4000)+","+
			(temper[5] +4100)+","+
			(temper[6] +4200)+","+
			(temper[7] +4300)+","+
			(temper[8] +4400)+","+
			(temper[9] +4500)+","+
			(temper[10]+4600)+","+
			(temper[11]+4700)+","+
			(temper[0] +4800)+","+
			(temper[1] +4900)+","+
			(temper[2] +5000);
		out.putString("presets", "0");
		out.putString("preset_0_name", "legacy temper");
		out.putString("preset_0_notes", notes);
	}
}
