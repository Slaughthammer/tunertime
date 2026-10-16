package org.galexander.tunertime;

import android.os.Bundle;
import android.preference.Preference;
import android.preference.PreferenceActivity;
import android.preference.PreferenceCategory;
import android.preference.PreferenceManager;
import android.view.ViewGroup;

public class Settings extends PreferenceActivity {
	private PreferenceCategory strobe_category;
	private Preference harmonics, organ_footages;

	public void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		addPreferencesFromResource(R.xml.preferences);
		/* edge-to-edge (Android 15+): keep the list clear of the
		 * action bar and navigation bar, like the other screens'
		 * layouts do with fitsSystemWindows */
		ViewGroup content = (ViewGroup)findViewById(android.R.id.content);
		if (content.getChildCount() > 0) {
			content.getChildAt(0).setFitsSystemWindows(true);
		}
		organ_rows_init();
	}

	/* organ mode offers organ footages in place of harmonic numbers */
	private void organ_rows_init() {
		strobe_category = (PreferenceCategory)findPreference(
						"strobe_category");
		harmonics = findPreference("harmonics");
		organ_footages = findPreference("organ_footages");
		Preference organ = findPreference("organ_mode");
		if ((strobe_category == null) || (harmonics == null) ||
		    (organ_footages == null) || (organ == null)) {
			return;
		}
		organ_rows_show(PreferenceManager.getDefaultSharedPreferences(
				this).getBoolean("organ_mode", false));
		final Preference.OnPreferenceChangeListener prev =
				organ.getOnPreferenceChangeListener();
		organ.setOnPreferenceChangeListener(
				new Preference.OnPreferenceChangeListener() {
			public boolean onPreferenceChange(Preference p, Object v) {
				if ((prev != null) &&
				    !prev.onPreferenceChange(p, v)) {
					return false;
				}
				organ_rows_show(((Boolean)v).booleanValue());
				return true;
			} });
	}

	private void organ_rows_show(boolean organ) {
		strobe_category.removePreference(harmonics);
		strobe_category.removePreference(organ_footages);
		strobe_category.addPreference(organ ? organ_footages
						    : harmonics);
	}
}
