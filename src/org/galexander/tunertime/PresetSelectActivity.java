package org.galexander.tunertime;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ListAdapter;
import android.widget.ListView;
import android.widget.SimpleAdapter;
import java.util.ArrayList;
import java.util.HashMap;

public class PresetSelectActivity extends Activity {
	private ListView list = null;

	public void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);

		parms.init(this);
		setContentView(R.layout.preset_select);
		
		list = (ListView)findViewById(R.id.preset_list);
		list.setEmptyView(findViewById(R.id.list_empty));
		list.setOnItemClickListener(
			new AdapterView.OnItemClickListener() {
			public void onItemClick(AdapterView<?> parent,
						View v, int pos, long id) {
				if ((pos < 0) ||
				    (pos >= preset.presets.size())) {
					return;
				}
				parms.preset_id = preset.presets.get(pos).id;
				startActivity(new Intent(v.getContext(),
						TunerTime.class));
			} });
	}

	public void onResume() {
		parms.load();
		list.setAdapter(new SimpleAdapter(this, presets_list(), 
			R.layout.list_entry, new String[] { "text" },
			new int[] { R.id.list_entry_text }));
		super.onResume();
	}

	public void onPause() {
		parms.save();
		super.onPause();
	}

	private ArrayList<HashMap<String, String> > presets_list() {
		ArrayList<HashMap<String, String> > ret =
			new ArrayList<HashMap<String, String> >();
		for (preset p : preset.presets) {
			HashMap<String, String> t =
				new HashMap<String, String>();
			t.put("text", p.name);
			ret.add(t);
		}
		return ret;
	}

	public void do_new(View v) {
		parms.preset_id = -1;
		startActivity(new Intent(this, PresetEditActivity.class));
	}
}
