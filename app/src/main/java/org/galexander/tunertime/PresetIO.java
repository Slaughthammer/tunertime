package org.galexander.tunertime;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/* Text format for preset import/export.  Pure Java (no android.*) so
 * that it can be tested on a desktop JDK.
 *
 * A note value is absolute pitch in tenths of a cent, A4 = 48000, the
 * same scale as preset.notes.  In the file each note is written as the
 * nearest equal-tempered note name with octave (C4 = middle C, octave
 * numbers change at C) plus a signed offset in cents with one decimal. */
public class PresetIO {
		/* units of preset.notes per cent.  MUST match
		 * preset.UNITS_PER_CENT (tenth-of-a-cent presets) */
	static final int UNITS_PER_CENT = 10;
	static final int SEMI = 100*UNITS_PER_CENT;
	static final int OCTAVE = 12*SEMI;
	static final int A4 = 48*SEMI;
		/* the range the strobe can show, A0..A8 */
	static final int MIN_NOTE = 0;
	static final int MAX_NOTE = 96*SEMI;

	private static final String NAMES[] = new String[] { "C", "C#", "D",
		"D#", "E", "F", "F#", "G", "G#", "A", "A#", "B" };
		/* semitones above C for the letters A..G */
	private static final int LETTER_SEMI[] = new int[] {
		9, 11, 0, 2, 4, 5, 7 };

	public static class entry {
		public String name;
		public List<Integer> notes;
		public entry(String name_, List<Integer> notes_) {
			name = name_;
			notes = notes_;
		}
	}

	public static class result {
		public List<entry> presets = new ArrayList<entry>();
		public List<String> errors = new ArrayList<String>();
	}

	public static final String HEADER =
		"# TunerTime presets\n" +
		"#\n" +
		"# Plain text, hand-editable.  Lines starting with # are comments.\n" +
		"# Each preset is a block:\n" +
		"#   preset <name>\n" +
		"#       <note><octave>  <cents>   (one note per line)\n" +
		"#   end\n" +
		"# <note> is C D E F G A B with an optional # or b (C#4 = Db4),\n" +
		"# <octave> follows scientific pitch: C4 is middle C, A4 is the\n" +
		"# reference A.  <cents> is the signed offset from equal\n" +
		"# temperament, 0.1 cent resolution (+0.0 if left out).\n";

	/* "C#4", "A0", ... for the semitone nearest v, and the offset from it
	 * in units (-500..+499, so +50.0 cents rounds up like note.java) */
	public static String note_name(int v) {
		int adj = fdiv(v + SEMI/2, SEMI) + 9;	/* C0 instead of A0 */
		return NAMES[adj - 12*fdiv(adj, 12)] + fdiv(adj, 12);
	}
	public static int note_offset(int v) {
		return v - fdiv(v + SEMI/2, SEMI)*SEMI;
	}
		/* Math.floorDiv() needs API 24 */
	private static int fdiv(int a, int b) {
		int q = a / b;
		return ((a % b != 0) && ((a < 0) != (b < 0))) ? q-1 : q;
	}

	public static String format_offset(int u) {
		int a = Math.abs(u);
		return ((u < 0) ? "-" : "+") + (a/UNITS_PER_CENT) + "." +
			(a%UNITS_PER_CENT);
	}

	public static String format_note(int v) {
		String n = note_name(v);
		while (n.length() < 4) {
			n += " ";
		}
		return n + " " + format_offset(note_offset(v));
	}

	public static String format(List<entry> l) {
		StringBuilder sb = new StringBuilder(HEADER);
		for (entry e : l) {
			sb.append("\npreset ").append(clean_name(e.name))
				.append("\n");
			for (int v : e.notes) {
				sb.append("\t").append(format_note(v)).append("\n");
			}
			sb.append("end\n");
		}
		return sb.toString();
	}

	/* names live on one line; fold any line breaks into spaces */
	public static String clean_name(String s) {
		s = s.replaceAll("[\\r\\n\\t]+", " ").trim();
		return (s.length() == 0) ? "unnamed" : s;
	}

	/* "Werckmeister" -> "Werckmeister (2)" if taken, and so on */
	public static String unique_name(String s, Collection<String> taken) {
		if (!taken.contains(s)) {
			return s;
		}
		String base = s;
		int i = 2;
		if (s.matches(".* \\(\\d+\\)")) {
			int k = s.lastIndexOf(" (");
			base = s.substring(0, k);
			try {
				i = Integer.parseInt(s.substring(k+2,
						s.length()-1)) + 1;
			} catch (NumberFormatException ex) {
				base = s;
			}
		}
		for (;; i++) {
			String t = base + " (" + i + ")";
			if (!taken.contains(t)) {
				return t;
			}
		}
	}

	private static boolean is_space(char c) {
		return (c == ' ') || (c == '\t') || (c == '\u00a0');
	}

	private static boolean is_digit(char c) {
		return (c >= '0') && (c <= '9');
	}

	/* "keyword rest" with case-insensitive keyword, or null */
	private static String keyword(String line, String kw) {
		int n = kw.length();
		if (!line.regionMatches(true, 0, kw, 0, n)) {
			return null;
		}
		if (line.length() == n) {
			return "";
		}
		if (!is_space(line.charAt(n))) {
			return null;
		}
		return line.substring(n).trim();
	}

	public static result parse(String text) {
		result r = new result();
		entry cur = null;
		if (text.startsWith("\ufeff")) {
			text = text.substring(1);
		}
		String lines[] = text.split("\r\n|\r|\n", -1);
		for (int i = 0; i < lines.length; i++) {
			int ln = i+1;
			String line = lines[i].replace('\u00a0', ' ').trim();
			if ((line.length() == 0) || (line.charAt(0) == '#')) {
				continue;
			}
			String rest;
			if ((rest = keyword(line, "preset")) != null) {
				if (cur != null) {
					r.presets.add(cur);
				}
				cur = new entry(clean_name(rest),
						new ArrayList<Integer>());
				continue;
			}
			if ((rest = keyword(line, "end")) != null) {
				if (cur == null) {
					r.errors.add("line "+ln+
						": \"end\" without \"preset\"");
				} else {
					r.presets.add(cur);
					cur = null;
				}
				continue;
			}
			if (cur == null) {
				r.errors.add("line "+ln+": \""+line+
					"\" outside of a preset (expected "+
					"\"preset <name>\")");
				continue;
			}
			String err = parse_note_line(line, cur.notes);
			if (err != null) {
				r.errors.add("line "+ln+": "+err);
			}
		}
		if (cur != null) {
			/* tolerate a missing "end" at the end of the file */
			r.presets.add(cur);
		}
		if ((r.presets.size() == 0) && (r.errors.size() == 0)) {
			r.errors.add("no presets found (expected "+
				"\"preset <name>\")");
		}
		return r;
	}

	/* strip a trailing comment: a # that starts a word */
	private static String strip_comment(String s) {
		for (int i = 1; i < s.length(); i++) {
			if ((s.charAt(i) == '#') && is_space(s.charAt(i-1))) {
				return s.substring(0, i).trim();
			}
		}
		return s;
	}

	/* parses e.g. "C#4 -9.8", "Db4-9.8c", "a4", "A-4 +02c" */
	private static String parse_note_line(String line, List<Integer> out) {
		String s = strip_comment(line);
		int n = s.length();
		int i = 0;
		char c = Character.toUpperCase(s.charAt(i));
		if ((c < 'A') || (c > 'G')) {
			return "expected a note name (C D E F G A B) "+
				"at \""+s+"\"";
		}
		int semi = LETTER_SEMI[c - 'A'];
		i++;
		while (i < n) {
			c = s.charAt(i);
			if ((c == '#') || (c == '\u266f')) {
				semi++;
			} else if ((c == 'b') || (c == '\u266d')) {
				semi--;
			} else {
				break;
			}
			i++;
		}
			/* "A-4" as the app displays it.  negative octaves
			 * are below the strobe's range anyways */
		if ((i < n) && (s.charAt(i) == '-')) {
			i++;
		} else {
			while ((i < n) && is_space(s.charAt(i))) {
				i++;
			}
		}
		int oct_start = i;
		long oct = 0;
		while ((i < n) && is_digit(s.charAt(i))
			&& (i - oct_start < 9)) {
			oct = oct*10 + (s.charAt(i) - '0');
			i++;
		}
		if (i == oct_start) {
			return "expected an octave number after the note "+
				"name in \""+s+"\" (e.g. A4)";
		}
		while ((i < n) && is_space(s.charAt(i))) {
			i++;
		}
		int off = 0;
		if (i < n) {
			String cs = s.substring(i).trim();
			if (cs.endsWith("cents")) {
				cs = cs.substring(0, cs.length()-5).trim();
			} else if (cs.endsWith("c")) {
				cs = cs.substring(0, cs.length()-1).trim();
			}
			Integer u = parse_cents(cs);
			if (u == null) {
				return "bad cents offset \""+s.substring(i)+
					"\" (expected e.g. +0.0 or -9.8)";
			}
			off = u;
		}
		long v = (oct*12 + semi - 9) * SEMI + off;
		if ((v < MIN_NOTE) || (v > MAX_NOTE)) {
			return "note \""+s+"\" is outside A0..A8";
		}
		out.add((int)v);
		return null;
	}

	/* "[+-]digits[.digits]" to units, rounded to the nearest unit */
	private static Integer parse_cents(String s) {
		int n = s.length();
		int i = 0;
		boolean neg = false;
		if ((i < n) && ((s.charAt(i) == '+') || (s.charAt(i) == '-') ||
				(s.charAt(i) == '\u2212'))) {
			neg = (s.charAt(i) != '+');
			i++;
			while ((i < n) && is_space(s.charAt(i))) {
				i++;
			}
		}
		long whole = 0;
		int digits = 0;
		while ((i < n) && is_digit(s.charAt(i)) &&
				(digits < 6)) {
			whole = whole*10 + (s.charAt(i) - '0');
			i++;
			digits++;
		}
		long frac = 0;	/* in hundredths of a unit */
		if ((i < n) && ((s.charAt(i) == '.') || (s.charAt(i) == ','))) {
			i++;
			long scale = UNITS_PER_CENT*100;
			while ((i < n) && is_digit(s.charAt(i))) {
				scale /= 10;
				frac += (s.charAt(i) - '0') * scale;
				i++;
				digits++;
			}
		}
		if ((digits == 0) || (i != n)) {
			return null;
		}
		long u = whole*UNITS_PER_CENT + (frac+50)/100;
		return (int)(neg ? -u : u);
	}
}
