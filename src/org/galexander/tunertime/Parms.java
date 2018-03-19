package org.galexander.tunertime;

public class Parms {
	static public volatile int strobe_cents = 4800;
	static public volatile int harm1_mult = 2, harm2_mult = 4;
	static public volatile String strobe_color = "redgreen";

	static public volatile boolean fft_mode = false;
			/* number of samples in FFT window */
	static public volatile int fft_size = 8192;
			/* zoom level */
	static public volatile int fft_width = Strobe.FFT_RANGE_MULT/4;
			/* offset in FFT_RANGE_MULTs of screen */
	static public volatile int fft_offset = 0;
	static public volatile boolean fft_grid_freq = false;

	static public volatile boolean changed = false;

	static public int A_freq = 4400;              /* 440.0 *10 */
	static public int cents_per_octave = 0;       /* *10 */
	static public int temper[] = new int[12];
	static public boolean temper_enabled;
};
