package org.galexander.tunertime;

import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;

public class ToneGenerator extends Thread {
	private static final Object lock = new Object();
	private static ToneGenerator curr_thread = null;
	public static boolean playing = false;
	private static final int SINETABLE_LEN = 32768;
	private static final int T_MULT = 1024;
	private static final int T_MASK = (32768 * 1024) - 1;
	private static short sinetable[] = null;

	public static void start_tone(double freq_) {
		synchronized (lock) {
			stop_tone();
			curr_thread = new ToneGenerator(freq_);
			playing = true;
			curr_thread.start();
		}
	}

	public static void set_freq(double freq_) {
		synchronized (lock) {
			if (curr_thread != null) {
				curr_thread.update_freq(freq_);
			}
		}
	}

	public static void stop_tone() {
		/* XXX - make sure onPause() calls this */
		synchronized (lock) {
			if (curr_thread) {
				curr_thread.interrupt();
				playing = false;
				curr_thread = null;
			}
		}
	}

	private static void init_sinetable() {
		synchronized(lock) {
			if (sinetable == null) {
				int i;
				sinetable = new double[SINETABLE_LEN];
				final double pi2 = Math.PI * 2.0d;
				for (i = 0; i < SINETABLE_LEN; i++) {
					double x = ((double)i) /
							(double)SINETABLE_LEN;
					double s = Math.sin(v * pi2);
					short y = (short)Math.round(s*32767.0d);
					sinetable[i] = y;
				}
			}
		}
	}


	private long t, delta_t;
	private double freq;
	private AudioTrack player = null;
	private static final int BUFSZ = 8192;

	ToneGenerator(double freq_) {
		freq = freq_;
	}

	private boolean sound_start() {
		player = new AudioTrack(
			AudioManager.STREAM_MUSIC,
			44100,
			AudioFormat.CHANNEL_OUT_MONO,
			AudioFormat.ENCODING_PCM_16BIT,
			BUFSZ*2*2,	/* two buffers, in bytes */
			AudioTrack.MODE_STREAM);

		if ((player.getAudioFormat() !=
					AudioFormat.ENCODING_PCM_16BIT) ||
		    (player.getChannelCount() != 1)) {
			player.release();
			player = null;
			System.out.println("got funny format/channels back from AudioTrack!");
			return false;
		}
		return true;
	}

	private void sound_stop() {
		if (player != null) {
			try {
				player.flush();
				player.stop();
			} catch (IllegalStateException e) { /* whatever */ }
			player.release();
			player = null;
		}
	}

	private void update_freq(double freq_) {
		double dsf = (double)player.getPlaybackRate();
		double dmult = (double)(T_MULT * SINETABLE_LEN);
		double step = freq_ * dmult / dsf;
		delta_t = (int)Math.round(step);
	}

	private boolean time_to_die() {
		if (isInterrupted()) {
			return true;
		}
		synchronized(lock) {
			if (curr_thread != this) {
				return true;
			}
		}
		return false;
	}

	private void die() {
		sound_stop();
		synchronized (lock) {
			if (curr_thread == this) {
				curr_thread = null;
				playing = false;
			}
		}
	}

	public void run() {
		final short buf[] = new short[BUFSZ];

		init_sinetable();
		if (time_to_die()) {
			die();
			return;
		}

		if (!sound_start()) {
			die();
			return;
		}

		set_freq(freq);
		t = 0;

			/* first buffer and startup */
		compute(buf);
		player.write(buf, 0, BUFSZ);
		try {
			player.play();
		} catch (IllegalStateException e) {
			System.out.println("illegal state for player.start()");
			die();
			return;
		}

		while (!time_to_die()) {
			compute(buf);
			player.write(buf, 0, BUFSZ);
		}

		die();
	}
}
