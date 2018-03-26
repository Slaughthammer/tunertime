package org.galexander.tunertime;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.graphics.LightingColorFilter;
import android.os.Bundle;
import android.view.inputmethod.EditorInfo;
import android.view.KeyEvent;
import android.view.View;
import android.widget.EditText;
import android.widget.TextView;
import java.util.List;

public class PresetNoteActivity extends Activity {
	private TextView pn_text;
	private EditText pn_cents;
	private View[] pn_notes = new View[7];
	private final static int[] pn_note_vals = new int[] { 0,2,4,5,7,9,11 };
	private View pn_sharp;
	private View[] pn_octs = new View[9];

	private List<Integer> notes;
	private int note_idx;

	/*        C  C# D  D# E  F  F# G  G# A  A# B */
	private static final int[] is_sharp = new int[]
		{ 0, 1, 0, 1, 0, 0, 1, 0, 1, 0, 1, 0 };
	private static final int[] can_sharpen = new int[]
		{ 1, 0, 1, 0, 0, 1, 0, 1, 0, 1, 0, 0 };
	private static final int[] sharp_swap = new int[]
		{ 1, 0, 3, 2, 4, 6, 5, 8, 7, 10,9, 11};
	private static final int[] no_sharps = new int[]
		{ 0, 0, 1, 1, 2, 3, 3, 4, 4, 5, 5, 6 };

	public void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);

		parms.init(this);
		setContentView(R.layout.preset_note);
		pn_text = (TextView)findViewById(R.id.pn_text);
		pn_cents = (EditText)findViewById(R.id.pn_cents);
		pn_cents.setOnEditorActionListener(
			new TextView.OnEditorActionListener() {
			public boolean onEditorAction(TextView v, int actionId,
							KeyEvent e) {
				if ((actionId == EditorInfo.IME_ACTION_DONE) ||
				    ((e != null) &&
				     (e.getKeyCode() ==
				      KeyEvent.KEYCODE_ENTER) &&
				     (e.getAction() == KeyEvent.ACTION_UP))) {
					try {
						set_cents(Integer.parseInt(
						       v.getText().toString()));
					} catch (Exception x) { }
				}
				return (actionId != EditorInfo.IME_ACTION_DONE);
			} });
		pn_notes[0] = findViewById(R.id.pn_note_0);
		pn_notes[1] = findViewById(R.id.pn_note_1);
		pn_notes[2] = findViewById(R.id.pn_note_2);
		pn_notes[3] = findViewById(R.id.pn_note_3);
		pn_notes[4] = findViewById(R.id.pn_note_4);
		pn_notes[5] = findViewById(R.id.pn_note_5);
		pn_notes[6] = findViewById(R.id.pn_note_6);
		pn_sharp = findViewById(R.id.pn_sharp);
		pn_octs[0] = findViewById(R.id.pn_oct_0);
		pn_octs[1] = findViewById(R.id.pn_oct_1);
		pn_octs[2] = findViewById(R.id.pn_oct_2);
		pn_octs[3] = findViewById(R.id.pn_oct_3);
		pn_octs[4] = findViewById(R.id.pn_oct_4);
		pn_octs[5] = findViewById(R.id.pn_oct_5);
		pn_octs[6] = findViewById(R.id.pn_oct_6);
		pn_octs[7] = findViewById(R.id.pn_oct_7);
		pn_octs[8] = findViewById(R.id.pn_oct_8);
	}

	public void onResume() {
		parms.load();
		super.onResume();
		preset p = preset.find(parms.preset_id);
		if (p == null) {
			finish();
		}
		notes = p.notes;
		note_idx = parms.preset_note;
		if ((note_idx < 0) || (note_idx >= notes.size())) {
			finish();
		}
		update();
	}

	public void onPause() {
		parms.save();
		super.onPause();
		notes = null;
	}

	private final static LightingColorFilter brightf =
			new LightingColorFilter(0xbbbbbb, 0x004444);
	private final static LightingColorFilter darkf =
			new LightingColorFilter(0x888888, 0x330000);
	private void set_brightness(View v, boolean bright) {
		v.getBackground().setColorFilter(bright ? brightf : darkf);
	}

	private void update() {
		int cents = notes.get(note_idx);
		if (cents < 0) {
			cents = 0;
			notes.set(note_idx, cents);
		} else if (cents > 9600) {
			cents = 9600;
			notes.set(note_idx, cents);
		}

		note n = new note(cents);
		pn_text.setText(n.full_str);
		pn_cents.setText(Integer.toString(n.cents));

		int t = no_sharps[n.note_idx];
		for (int i = 0; i < pn_notes.length; i++) {
			set_brightness(pn_notes[i], (t == i));
		}
		set_brightness(pn_sharp, (is_sharp[n.note_idx] != 0));
		for (int i = 0; i < pn_octs.length; i++) {
			set_brightness(pn_octs[i], (n.octave == i));
		}
	}

	public void do_note(View v) {
		int idx = 0;
		for (int i = 0; i < pn_notes.length; i++) {
			if (v == pn_notes[i]) {
				idx = pn_note_vals[i];
				break;
			}
		}
		note n = new note(notes.get(note_idx));
		notes.set(note_idx, (n.octave*1200) +
			((idx+can_sharpen[idx]*is_sharp[n.note_idx])*100) +
			n.cents - 900);
		update();
	}

	public void do_sharp(View v) {
		note n = new note(notes.get(note_idx));
		notes.set(note_idx, (n.octave*1200) +
			(sharp_swap[n.note_idx]*100) + n.cents - 900);
		update();
	}

	public void do_oct(View v) {
		int oct = 4;
		for (int i = 0; i < pn_octs.length; i++) {
			if (v == pn_octs[i]) {
				oct = i;
				break;
			}
		}
		note n = new note(notes.get(note_idx));
		notes.set(note_idx,
			(oct*1200) + (n.note_idx*100) + n.cents - 900);
		update();
	}

	private void set_cents(int c) {
		int prev = notes.get(note_idx);
		note n = new note(prev);
		int next = (n.octave*1200) + (n.note_idx*100) + c - 900;
		if (prev != next) {
			notes.set(note_idx, next);
			update();
		}
	}

	public void do_save(View v) {
		finish();
	}

	public void do_delete(View v) {
		DialogInterface.OnClickListener listener =
			new DialogInterface.OnClickListener() {
			public void onClick(DialogInterface d, int which) {
				if (which == DialogInterface.BUTTON_POSITIVE) {
					notes.remove(note_idx);
					notes = null;
					parms.preset_note = 0;
					finish();
				}
			} };
		new AlertDialog.Builder(this)
			.setTitle("Confirm")
			.setMessage("Really delete \"" +
				new note(notes.get(note_idx)).full_str + "\"?")
			.setPositiveButton("Delete", listener)
			.setNegativeButton("Cancel", listener)
			.show();
	}
}
