package org.galexander.tunertime;

import android.os.Bundle;
import android.preference.ListPreference;
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
		setup_audio();
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

	/* the microphone list depends on what is plugged in, and "auto"
	 * says which source it amounts to on this phone */
	private void setup_audio() {
		MicSelect.init(this);
		ListPreference src = (ListPreference)findPreference("audio_source");
		if (src != null) {
			CharSequence[] e = src.getEntries().clone();
			e[0] = MicSelect.unprocessed_supported()
				? "Auto (unprocessed)" : "Auto (voice recognition)";
			src.setEntries(e);
		}
		ListPreference mic = (ListPreference)findPreference("microphone");
		if (mic != null) {
			String[][] c = MicSelect.mic_choices();
			mic.setEntries(c[0]);
			mic.setEntryValues(c[1]);
			if (mic.findIndexOfValue(mic.getValue()) < 0) {
				/* device no longer present: falls back to
				 * the system default when recording */
				mic.setSummary("Selected device not connected");
			}
		}
	}
}
