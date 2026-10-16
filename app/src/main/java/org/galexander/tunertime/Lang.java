package org.galexander.tunertime;

import android.app.LocaleManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.os.Build;
import android.os.LocaleList;
import android.preference.PreferenceManager;
import java.util.Locale;

/* Settings -> Main -> "Language / Sprache": "system", "en" or "de".
 *
 * Android 13+ has a per-app language (LocaleManager), which the system
 * settings can change as well (res/xml/locales_config.xml); it applies to
 * the whole app and recreates the activities by itself.  The preference
 * then only mirrors it.
 *
 * Older versions: every activity wraps its base context in wrap(), which
 * overrides the locale of its configuration, and TunerTime recreates
 * itself when it comes back with another language (check()).  Code with
 * only the application context (MicSelect) uses res(). */
public class Lang {
	public static final String KEY = "language";

	private static SharedPreferences prefs(Context c) {
		return PreferenceManager.getDefaultSharedPreferences(c);
	}

	/* the setting as it is in effect now */
	public static String get(Context c) {
		if (Build.VERSION.SDK_INT >= 33) {
			LocaleList l = c.getSystemService(LocaleManager.class)
						.getApplicationLocales();
			if (l.isEmpty()) {
				return "system";
			}
			String t = l.get(0).getLanguage();
			return (t.equals("en") || t.equals("de")) ? t : "system";
		}
		return prefs(c).getString(KEY, "system");
	}

	/* called when the setting changes; on older versions the caller
	 * recreates the activity once the value is stored */
	public static void set(Context c, String v) {
		if (Build.VERSION.SDK_INT >= 33) {
			c.getSystemService(LocaleManager.class)
				.setApplicationLocales(v.equals("system")
					? LocaleList.getEmptyLocaleList()
					: LocaleList.forLanguageTags(v));
		}
	}

	/* for Activity.attachBaseContext() */
	public static Context wrap(Context base) {
		if (Build.VERSION.SDK_INT >= 33) {
			return base;
		}
		String v = prefs(base).getString(KEY, "system");
		if (v.equals("system")) {
			return base;
		}
		Configuration conf = new Configuration(
				base.getResources().getConfiguration());
		conf.setLocale(new Locale(v));
		return base.createConfigurationContext(conf);
	}

	/* resources in the app's language, for any context */
	public static Resources res(Context c) {
		return wrap(c).getResources();
	}
}
