package org.galexander.tunertime;

import java.util.ArrayList;
import java.util.List;

/* Well-known historical temperaments for keyboard instruments.  Each table
 * is the textbook deviation from equal temperament in cents for
 * C C# D Eb E F F# G G# A Bb B, with C = 0.  make_notes() shifts them so
 * A = 0, so the A-freq setting keeps meaning the A you hear. */
public class temperaments {
	public static final String[] names = {
		"Equal temperament",
		"Pythagorean",
		"Meantone 1/4 comma",
		"Meantone 1/6 comma",
		"Werckmeister III",
		"Kirnberger III",
		"Vallotti",
		"Young II",
		"Kellner (Bach)",
	};

	public static final String[] about = {
		"all semitones equal; to switch back from a historical one",
		"pure fifths, wolf G#-Eb",
		"pure major thirds, wolf G#-Eb; Renaissance/early Baroque",
		"Baroque organs, mild wolf G#-Eb",
		"1691, well-tempered, all keys usable",
		"1779, pure C-E third",
		"c. 1750, six 1/6 Pythagorean comma fifths",
		"1800, like Vallotti centred on C",
		"1977 reconstruction of Bach's tuning",
	};

	private static final double[][] cents = {
		{ 0,   0,     0,     0,      0,     0,      0,     0,      0,      0,     0,      0    },
		{ 0,  13.69,  3.91, -9.78,   7.82, -1.96,  11.73,  1.96,  15.64,   5.87, -3.91,   9.78 },
		{ 0, -23.95, -6.84, 10.26, -13.69,  3.42, -20.53, -3.42, -27.37, -10.26,  6.84, -17.11 },
		{ 0, -11.40, -3.26,  4.89,  -6.52,  1.63,  -9.78, -1.63, -13.03,  -4.89,  3.26,  -8.15 },
		{ 0,  -9.78, -7.82, -5.87,  -9.78, -1.96, -11.73, -3.91,  -7.82, -11.73, -3.91,  -7.82 },
		{ 0,  -9.78, -6.84, -5.87, -13.69, -1.96,  -9.78, -3.42,  -7.82, -10.26, -3.91, -11.73 },
		{ 0,  -5.87, -3.91, -1.96,  -7.82,  1.96,  -7.82, -1.96,  -3.91,  -5.87,  0.00,  -9.78 },
		{ 0,  -9.78, -3.91, -5.87,  -7.82, -1.96, -11.73, -1.96,  -7.82,  -5.87, -3.91,  -9.78 },
		{ 0,  -9.78, -5.47, -5.87, -10.95, -1.96, -11.73, -2.74,  -7.82,  -8.21, -3.91,  -8.99 },
	};

	/* the 12 notes C4..B4 in preset units (tenths of a cent, 48000 = A4),
	 * normalised so A has no offset */
	public static List<Integer> make_notes(int idx) {
		double[] c = cents[idx];
		List<Integer> ret = new ArrayList<Integer>();
		int c4 = 3900*preset.UNITS_PER_CENT;
		for (int i = 0; i < 12; i++) {
			double off = c[i] - c[9];
			ret.add(c4 + 100*preset.UNITS_PER_CENT*i +
				(int)Math.round(off*preset.UNITS_PER_CENT));
		}
		return ret;
	}
}
