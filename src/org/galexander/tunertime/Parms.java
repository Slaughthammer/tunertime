package org.galexander.tunertime;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.preference.PreferenceManager;
import java.util.Map;

public class Parms {
	static public volatile int strobe_cents = 4800;
	static public volatile int harm1_mult = 2, harm2_mult = 4;
	static public volatile String strobe_color = "redgreen";

	static public volatile boolean fft_mode = false;
			/* number of samples in FFT window */
	static public volatile int fft_size = 8192;
			/* zoom level */
	static public volatile int fft_width = Strobe.FFT_RANGE_MULT/4;
			/* offset in FFT_RANGE_MULTs of screen */
	static public volatile int fft_offset = 0;
	static public volatile boolean fft_grid_freq = false;

	static public volatile boolean changed = false;

	static public int A_freq = 4400;              /* 440.0 *10 */
	static public int cents_per_octave = 0;       /* *10 */
	static public int temper[] = new int[12];
	static public boolean temper_enabled;

	private static SharedPreferences prefs = null;


	public static void init(Activity a) {
		if (prefs == null) {
			prefs = PreferenceManager.getDefaultSharedPreferences(a);
			check_for_legacy(a);
		}
	}
	public static void load() {
		Parms.A_freq = prefs.getInt("A_freq", 4400);
		if (Parms.A_freq <= 880) {
			/* the setting must come from before we encoded A_freq
			 * as *10 (so there is one decimal point of info) */
			Parms.A_freq *= 10;
		}
		Parms.cents_per_octave = prefs.getInt("cents_per_octave", 0);
		Parms.strobe_cents = prefs.getInt("strobe_cents", 4800);
		Parms.harm1_mult = Integer.parseInt(prefs.getString("harm1", "2"));
		Parms.harm2_mult = Integer.parseInt(prefs.getString("harm2", "4"));
		Parms.strobe_color = prefs.getString("strobe_color", "redgreen");
		Parms.temper[0] = prefs.getInt("temper0", 0);
		Parms.temper[1] = prefs.getInt("temper1", 0);
		Parms.temper[2] = prefs.getInt("temper2", 0);
		Parms.temper[3] = prefs.getInt("temper3", 0);
		Parms.temper[4] = prefs.getInt("temper4", 0);
		Parms.temper[5] = prefs.getInt("temper5", 0);
		Parms.temper[6] = prefs.getInt("temper6", 0);
		Parms.temper[7] = prefs.getInt("temper7", 0);
		Parms.temper[8] = prefs.getInt("temper8", 0);
		Parms.temper[9] = prefs.getInt("temper9", 0);
		Parms.temper[10] = prefs.getInt("temper10", 0);
		Parms.temper[11] = prefs.getInt("temper11", 0);
		Parms.temper_enabled = prefs.getBoolean("temper_enabled", false);
		Parms.fft_mode = prefs.getBoolean("fft_mode", false);
		Parms.fft_size = prefs.getInt("fft_size", 2048);
		Parms.fft_width = prefs.getInt("fft_width", Strobe.FFT_RANGE_MULT/4);
		Parms.fft_offset = prefs.getInt("fft_offset", 0);
		Parms.fft_grid_freq = prefs.getBoolean("fft_grid_freq", false);
	}

	public static void save() {
		SharedPreferences.Editor editor = prefs.edit();
		editor.putInt("A_freq", Parms.A_freq);
		editor.putInt("cents_per_octave", Parms.cents_per_octave);
		editor.putInt("strobe_cents", Parms.strobe_cents);
		editor.putInt("temper0", Parms.temper[0]);
		editor.putInt("temper1", Parms.temper[1]);
		editor.putInt("temper2", Parms.temper[2]);
		editor.putInt("temper3", Parms.temper[3]);
		editor.putInt("temper4", Parms.temper[4]);
		editor.putInt("temper5", Parms.temper[5]);
		editor.putInt("temper6", Parms.temper[6]);
		editor.putInt("temper7", Parms.temper[7]);
		editor.putInt("temper8", Parms.temper[8]);
		editor.putInt("temper9", Parms.temper[9]);
		editor.putInt("temper10", Parms.temper[10]);
		editor.putInt("temper11", Parms.temper[11]);
		editor.putBoolean("temper_enabled", Parms.temper_enabled);
		editor.putBoolean("fft_mode", Parms.fft_mode);
		editor.putInt("fft_size", Parms.fft_size);
		editor.putInt("fft_width", Parms.fft_width);
		editor.putInt("fft_offset", Parms.fft_offset);
		editor.putBoolean("fft_grid_freq", Parms.fft_grid_freq);
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
			} else if (value instanceof Integer) {
				editor.putInt(key, ((Integer)value).intValue());
			} else if (value instanceof Boolean) {
				editor.putBoolean(key, ((Boolean)value).booleanValue());
			}
		}
		editor.commit();
		oldvals = null;

		editor = old.edit();
		editor.clear();
		editor.commit();
	}
};
