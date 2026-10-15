package org.galexander.tunertime;

public class note {
	public int note_idx;
	public String note_str;
	public int cents;		/* tenths of a cent (preset.UNITS_PER_CENT) */
	public String cents_str;
	public int octave;
	public String full_str;

	private static final String NOTE_NAMES[] = new String[] { "C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B" };

	private static final int U = preset.UNITS_PER_CENT;

		/* cents_ is in tenths of a cent, 48000 = A4 */
	public note(int cents_) {
		int adj_cents = cents_+900*U;	/* based on C instead of A */

		note_idx = ((adj_cents+50*U)/(100*U))%12;
		note_str = NOTE_NAMES[note_idx];

		cents = (((adj_cents%(100*U))+50*U)%(100*U))-50*U;
		cents_str = cents_str(cents);

		octave = (adj_cents+50*U)/(1200*U);

		full_str = note_str + "-" + octave + " " + cents_str;
	}

		/* tenths of a cent -> "+03.5c" */
	public static String cents_str(int c) {
		int a = Math.abs(c);
		return ((c < 0) ? "-" : "+") + ((a < 10*U) ? "0" : "") +
			(a/U) + "." + (a%U) + "c";
	}

		/* tenths of a cent -> "3.5" (for editing) */
	public static String cents_edit_str(int c) {
		int a = Math.abs(c);
		return ((c < 0) ? "-" : "") + (a/U) + "." + (a%U);
	}
}
