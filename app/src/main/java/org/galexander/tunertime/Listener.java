package org.galexander.tunertime;

import java.util.concurrent.Semaphore;

public class Listener extends Thread {
	private static final int BUFSZ = 1024;
	private static final int NUMBUFS = 32;

	private static final Object lock = new Object();
	private volatile static Listener curr_thread = null;
	private static final Semaphore sem = new Semaphore(1);

	private volatile static int samplerate = 44100;

	private volatile static short[][] bufs = new short[NUMBUFS][BUFSZ];
				/* bufs_r == bufs_w --> empty */
	private volatile static int bufs_r = 0;
	private volatile static int bufs_w = 0;
		/* MicSelect.switches when each buffer was recorded, and for
		 * the buffer read last */
	private static final int[] gens = new int[NUMBUFS];
	private volatile static int read_gen = 0;
	

	public static void start_recording() {
		synchronized (lock) {
			stop_recording();
			curr_thread = new Listener();
			curr_thread.start();
		}
	}

	public static int read(short[] dst, int len) {
		int n = 0;
		synchronized (lock) {
			if (curr_thread == null) {
				return -1;
			}
		}
		while ((n+BUFSZ) <= len) {
			if (bufs_r == bufs_w) {
				return n;
			}
			System.arraycopy(bufs[bufs_r], 0, dst, n, BUFSZ);
			read_gen = gens[bufs_r];
			bufs_r = (bufs_r + 1) % NUMBUFS;
			n += BUFSZ;
		}
		return n;
	}

	/* changes when the samples read last came through another
	 * microphone (or channel) than before; see mixture */
	public static int buffer_gen() {
		return read_gen;
	}

	public static int get_samplerate() {
		return samplerate;
	}

	public static void stop_recording() {
		synchronized (lock) {
			if (curr_thread != null) {
				curr_thread.interrupt();
				curr_thread = null;
			}
		}
	}

	
	/* the thread itself: */
		/* audio source and microphone choice (Settings "Audio") */
	private MicSelect recorder = null;

	private boolean sound_init() {
		recorder = new MicSelect();
		if (!recorder.open(BUFSZ)) {
			recorder = null;
			return false;
		}
		samplerate = recorder.samplerate();
		return true;
	}

	private void sound_stop() {
		if (recorder == null) {
			return;
		}
		recorder.close();
		recorder = null;
	}

	public void run() {
		try {
			sem.acquire();
		} catch (Exception e) {
			die();
			return;
		}
		if (sound_init()) {
			while (!time_to_die()) {
				if (!recorder.read(bufs[bufs_w], BUFSZ)) {
					break;
				}
				gens[bufs_w] = MicSelect.switches;
				bufs_w = (bufs_w + 1) % NUMBUFS;
				if (bufs_w == bufs_r) {
					System.out.println("Listener record buffer overflow");
				}
			}
		}
		sound_stop();
		die();
		sem.release();
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
		synchronized(lock) {
			if (curr_thread == this) {
				curr_thread = null;
			}
		}
	}
}
