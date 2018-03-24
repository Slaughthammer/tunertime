package org.galexander.tunertime;

import android.app.Activity;
import android.os.Bundle;
import android.widget.EditText;
import android.widget.ListView;
import android.widget.SimpleAdapter;
import java.util.ArrayList;
import java.util.HashMap;

public class PresetEditActivity extends Activity {
	private ListView list = null;

	public void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);

		parms.init(this);
		setContentView(R.layout.preset_edit);

		list = (ListView)findViewById(R.id.note_list);
		list.setEmptyView(findViewById(R.id.list_empty));
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
