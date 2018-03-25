package org.galexander.tunertime;

import java.text.DecimalFormat;

public class note {
	public int note_idx;
	public String note_str;
	public int cents;
	public String cents_str;
	public int octave;
	public String full_str;

	private static final String NOTE_NAMES[] = new String[] { "C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B" };

	public note(int cents_) {
		int adj_cents = cents_+900;	/* based on C instead of A */

		note_idx = ((adj_cents+50)/100)%12;
		note_str = NOTE_NAMES[note_idx];

		cents = (((adj_cents%100)+50)%100)-50;
		DecimalFormat cents_fmt = new DecimalFormat("+00c;-00c");
		cents_str = cents_fmt.format(cents);

		octave = (adj_cents+50)/1200;

		full_str = note_str + "-" + octave + " " + cents_str;
	}
}
