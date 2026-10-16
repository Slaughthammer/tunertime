package org.galexander.tunertime;

public class note {
	public int note_idx;
	public String note_str;	/* in the note-names setting: "F#", "Fis",
				 * or Helmholtz "fis'" (includes the octave) */
	public int cents;		/* tenths of a cent (preset.UNITS_PER_CENT) */
	public String cents_str;
	public int octave;
	public String name_oct;	/* with the octave: "F#4", "fis'" */
	public String full_str;

	private static final String NOTE_NAMES[] = new String[] { "C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B" };
		/* German: B is English Bb, H is English B */
	private static final String GERMAN_NAMES[] = new String[] { "C", "Cis", "D", "Dis", "E", "F", "Fis", "G", "Gis", "A", "B", "H" };

	private static final int U = preset.UNITS_PER_CENT;

		/* cents_ is in tenths of a cent, 48000 = A4 */
	public note(int cents_) {
		int adj_cents = cents_+900*U;	/* based on C instead of A */

		note_idx = ((adj_cents+50*U)/(100*U))%12;

		cents = (((adj_cents%(100*U))+50*U)%(100*U))-50*U;
		cents_str = cents_str(cents);

		octave = (adj_cents+50*U)/(1200*U);

		if (helmholtz()) {
			note_str = helmholtz(note_idx, octave);
			name_oct = note_str;
			full_str = note_str + " " + cents_str;
		} else {
			note_str = class_name(note_idx);
			name_oct = note_str + octave;
			full_str = note_str + "-" + octave + " " + cents_str;
		}
	}

		/* the note-names setting asks for Helmholtz octaves */
	public static boolean helmholtz() {
		return parms.note_names.equals("helmholtz");
	}

		/* name of pitch class idx (0 = C), without octave */
	public static String class_name(int idx) {
		return (parms.note_names.equals("english") ? NOTE_NAMES
						: GERMAN_NAMES)[idx];
	}

		/* Helmholtz notation as German organ builders use it, written
		 * in ASCII: lower case from the small octave up with one prime
		 * per octave higher; upper case for the great octave with one
		 * comma per octave lower (commas after the name, as in English
		 * Helmholtz; German print puts a subscript first, e.g. 1C).
		 *   C,, = C0 (Subkontra-C)    C, = C1 (Kontra-C, 32')
		 *   C   = C2 (grosses C, 8')  c  = C3 (kleines c)
		 *   c'  = C4 (middle C)       c'' = C5, a' = A4 */
	public static String helmholtz(int idx, int oct) {
		String s = GERMAN_NAMES[idx];
		if (oct <= 2) {
			for (int i = oct; i < 2; i++) {
				s += ",";
			}
		} else {
			s = s.toLowerCase();
			for (int i = 3; i < oct; i++) {
				s += "'";
			}
		}
		return s;
	}

		/* the strobe's corner label: "A49" (piano key 49 = A4), or with
		 * Helmholtz names just the name, e.g. "a'" */
	public static String piano_name(int cents_) {
		note n = new note(cents_);
		if (helmholtz()) {
			return n.note_str;
		}
		int key = (cents_+50*U)/(100*U)+1;
		return n.note_str + (((key < 1) || (key > 88)) ? "__"
							: Integer.toString(key));
	}

		/* translates note names in English text (C D E F G A B with an
		 * optional # or b, not part of a longer word, e.g. "wolf G#-Eb")
		 * to the German names ("wolf Gis-Es") if those are selected */
	public static String names_in(String s) {
		if (parms.note_names.equals("english")) {
			return s;
		}
		StringBuilder sb = new StringBuilder();
		int n = s.length();
		for (int i = 0; i < n; i++) {
			char c = s.charAt(i);
			int idx = "C D EF G A B".indexOf(c);
			int len = 1;
			char acc = (i+1 < n) ? s.charAt(i+1) : ' ';
			if ((acc == '#') || (acc == 'b')) {
				len = 2;
			}
			if ((c == ' ') || (idx < 0) ||
			    ((i > 0) && Character.isLetter(s.charAt(i-1))) ||
			    ((i+len < n) && Character.isLetter(s.charAt(i+len)))) {
				sb.append(c);	/* not a note name */
				continue;
			}
			if (acc == 'b') {
				/* Ces Des Es Fes Ges As B */
				sb.append((c == 'E') ? "Es" : (c == 'A') ? "As" :
					(c == 'B') ? "B" : (c + "es"));
			} else {
				sb.append(GERMAN_NAMES[(idx+len-1)%12]);
			}
			i += len-1;
		}
		return sb.toString();
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
