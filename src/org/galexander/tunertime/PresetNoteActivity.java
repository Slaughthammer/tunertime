package org.galexander.tunertime;

import android.app.Activity;
import android.os.Bundle;

public class PresetNoteActivity extends Activity {
	public void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);

		parms.init(this);
		setContentView(R.layout.preset_note);
		/* XXX - (fooView)findViewById(R.id.xxx); */
	}

	public void onResume() {
		parms.load();
		super.onResume();
	}

	public void onPause() {
		parms.save();
		super.onPause();
	}

	
}
