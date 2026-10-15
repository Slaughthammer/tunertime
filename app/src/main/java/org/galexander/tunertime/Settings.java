package org.galexander.tunertime;

import android.os.Bundle;
import android.preference.PreferenceActivity;
import android.view.ViewGroup;

public class Settings extends PreferenceActivity {
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
	}
}
