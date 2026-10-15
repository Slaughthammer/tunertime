package org.galexander.tunertime;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.preference.PreferenceManager;
import java.util.Map;

public class parms {
	static public volatile int strobe_cents = 4800;
	static public volatile int harm0_mult = 1, harm1_mult = 2,
				harm2_mult = 4;
	static public volatile String strobe_color = "matrix";

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

	static public boolean preset_mode = false;
	static public int preset_id = -1;
	static public int preset_note = 0;
	static public int preset_oct = 0;

	private static SharedPreferences prefs = null;


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
		strobe_cents = prefs.getInt("strobe_cents", 4800);
		harm0_mult = Integer.parseInt(prefs.getString("harm0", "1"));
		harm1_mult = Integer.parseInt(prefs.getString("harm1", "2"));
		harm2_mult = Integer.parseInt(prefs.getString("harm2", "4"));
		strobe_color = prefs.getString("strobe_color", "matrix");
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

	public static void save() {
		SharedPreferences.Editor editor = prefs.edit();
		editor.putInt("A_freq", A_freq);
		editor.putInt("cents_per_octave", cents_per_octave);
		editor.putInt("strobe_cents", strobe_cents);
		editor.putBoolean("fft_mode", fft_mode);
		editor.putInt("fft_size", fft_size);
		editor.putInt("fft_width", fft_width);
		editor.putInt("fft_height", fft_height);
		editor.putInt("fft_xoffset", fft_xoffset);
		editor.putInt("fft_yoffset", fft_yoffset);
		editor.putBoolean("fft_grid_freq", fft_grid_freq);
		editor.putBoolean("preset_mode", preset_mode);
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
