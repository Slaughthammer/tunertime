package org.galexander.tunertime;

import android.app.Activity;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.widget.EditText;
import android.widget.TextView;
import java.util.ArrayList;

public class PresetNoteActivity extends Activity {
	private TextView pn_text;
	private EditText pn_cents;
	private ArrayList<Integer> notes;
	private int note_idx;

	public void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);

		parms.init(this);
		setContentView(R.layout.preset_note);
		pn_text = (TextView)findViewById(R.id.pn_text);
		pn_cents = (EditText)findViewById(R.id.pn_cents);
		pn_cents.addTextChangedListener(new TextWatcher() {
			public void afterTextChanged(Editable s) {
				try {
					set_cents(Integer.parseInt(
								s.toString()));
				} catch (Exception e) { }
			}
			public void beforeTextChanged(CharSequence s,
					int start, int count, int after) {}
			public void onTextChanged(CharSequence s, int start,
					int before, int count) {}
			});
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
		update(/*for_cents=*/false);
	}

	public void onPause() {
		parms.save();
		super.onPause();
		notes = null;
	}

	private void update(boolean for_cents) {
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
		if (!for_cents) {
			pn_cents.setText(Integer.toString(n.cents));
		}
		/* XXX - lighten/darken the buttons? */
	}

	private static final int[] sharp_test = new int[]
			{ 0,1,0,1,0,0,1,0,1,0,1,0 };
	private static final int[] can_sharpen = new int[]
			{ 1,0,1,0,0,1,0,1,0,1,0,0 };
	private void set_note(int idx) {
		note n = new note(notes.get(note_idx));
		notes.set(note_idx, (n.octave*1200) +
			((idx+can_sharpen[idx]*sharp_test[n.note_idx])*100) +
			n.cents - 900);
		update(/*for_cents=*/false);
	}
	public void do_note_C(View v) { set_note(0); }
	public void do_note_D(View v) { set_note(2); }
	public void do_note_E(View v) { set_note(4); }
	public void do_note_F(View v) { set_note(5); }
	public void do_note_G(View v) { set_note(7); }
	public void do_note_A(View v) { set_note(9); }
	public void do_note_B(View v) { set_note(11); }

	private static final int[] sharp_swap = new int[]
			{ 1,0,3,2,4,6,5,8,7,10,9,11 };
	public void do_sharp(View v) {
		note n = new note(notes.get(note_idx));
		notes.set(note_idx, (n.octave*1200) +
			(sharp_swap[n.note_idx]*100) + n.cents - 900);
		update(/*for_cents=*/false);
	}

	private void set_oct(int oct) {
		note n = new note(notes.get(note_idx));
		notes.set(note_idx,
			(oct*1200) + (n.note_idx*100) + n.cents - 900);
		update(/*for_cents=*/false);
	}
	public void do_oct_0(View v) { set_oct(0); }
	public void do_oct_1(View v) { set_oct(1); }
	public void do_oct_2(View v) { set_oct(2); }
	public void do_oct_3(View v) { set_oct(3); }
	public void do_oct_4(View v) { set_oct(4); }
	public void do_oct_5(View v) { set_oct(5); }
	public void do_oct_6(View v) { set_oct(6); }
	public void do_oct_7(View v) { set_oct(7); }
	public void do_oct_8(View v) { set_oct(8); }

	private void set_cents(int c) {
		int prev = notes.get(note_idx);
		note n = new note(prev);
		int next = (n.octave*1200) + (n.note_idx*100) + c - 900;
		if (prev != next) {
			notes.set(note_idx, next);
			update(/*for_cents=*/true);
		}
	}

	public void do_save(View v) {
		finish();
	}

	public void do_delete(View v) {
		/* XXX */
	}
}
