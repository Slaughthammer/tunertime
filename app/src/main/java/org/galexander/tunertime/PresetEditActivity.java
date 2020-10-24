package org.galexander.tunertime;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.AdapterView;
import android.widget.EditText;
import android.widget.ListView;
import android.widget.SimpleAdapter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

public class PresetEditActivity extends Activity {
	private EditText name_view = null;
	private ListView list = null;

	public void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);

		parms.init(this);
		setContentView(R.layout.preset_edit);

		name_view = (EditText)findViewById(R.id.preset_name);
		list = (ListView)findViewById(R.id.note_list);
		list.setEmptyView(findViewById(R.id.list_empty));
		list.setOnItemClickListener(
			new AdapterView.OnItemClickListener() {
			public void onItemClick(AdapterView<?> parent,
						View v, int pos, long id) {
				preset p = get_preset();
				if ((pos < 0) || (pos >= p.notes.size())) {
					return;
				}
				parms.preset_note = pos;
				startActivity(new Intent(v.getContext(),
						PresetNoteActivity.class));
			} });
	}

	public void onResume() {
		parms.load();
		list.setAdapter(new SimpleAdapter(this, notes_list(),
			R.layout.list_entry, new String[] { "text" },
			new int[] { R.id.list_entry_text }));
		name_view.setText(get_preset().name);
		super.onResume();
	}

	public void onPause() {
		if (parms.preset_id != -1) {
			get_preset().name = name_view.getText().toString();
		}
		parms.save();
		super.onPause();
	}

	private preset get_preset() {
		preset p = preset.find(parms.preset_id);
		if (p == null) {
			p = preset.make();
			parms.preset_id = p.id;
		}
		return p;
	}

	private List<HashMap<String, String> > notes_list() {
		List<HashMap<String, String> > ret =
			new ArrayList<HashMap<String, String> >();
		preset p = get_preset();
		p.sort_notes();
		for (Integer i : p.notes) {
			note n = new note(i);
			HashMap<String, String> t =
				new HashMap<String, String>();
			t.put("text", n.full_str);
			ret.add(t);
		}
		return ret;
	}

	public void do_new(View v) {
		List<Integer> notes = get_preset().notes;
		int n = notes.size();
		notes.add((n >= 1) ? notes.get(n-1) : 4800);
		parms.preset_note = n;
		startActivity(new Intent(v.getContext(),
				PresetNoteActivity.class));
	}

	public void do_delete(View v) {
		DialogInterface.OnClickListener listener =
			new DialogInterface.OnClickListener() {
			public void onClick(DialogInterface d, int which) {
				if (which == DialogInterface.BUTTON_POSITIVE) {
					preset.presets.remove(get_preset());
					parms.preset_id = -1;
					finish();
				}
			} };
		new AlertDialog.Builder(this)
			.setTitle("Confirm")
			.setMessage("Really delete \""+get_preset().name+"\"?")
			.setPositiveButton("Delete", listener)
			.setNegativeButton("Cancel", listener)
			.show();
	}
}
