package org.galexander.tunertime;

import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
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
			bufs_r = (bufs_r + 1) % NUMBUFS;
			n += BUFSZ;
		}
		return n;
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
	private AudioRecord recorder = null;

	private boolean sound_init() {
		int ar_bufsz = AudioRecord.getMinBufferSize(
				44100, AudioFormat.CHANNEL_IN_MONO,
				AudioFormat.ENCODING_PCM_16BIT);
		ar_bufsz *= 2;		/* at least two hardware buffers */
		ar_bufsz = Math.max(ar_bufsz, (44100/4)*2); /* at least 250ms */

		recorder = new AudioRecord(MediaRecorder.AudioSource.MIC,
				44100, AudioFormat.CHANNEL_IN_MONO,
				AudioFormat.ENCODING_PCM_16BIT, ar_bufsz);
		if (recorder.getState() != AudioRecord.STATE_INITIALIZED) {
			System.out.println("failed to initialize audio\n");
			return false;
		}
		if ((recorder.getAudioFormat() !=
					AudioFormat.ENCODING_PCM_16BIT) ||
		    (recorder.getChannelCount() != 1)) {
			System.out.println("got funny format/channels back from AudioRecord!");
			return false;
		}
		try {
			recorder.startRecording();
		} catch (IllegalStateException e) {
			System.out.println("illegal state for startRecording()");
			return false;
		}
		samplerate = recorder.getSampleRate();
		
		return true;
	}

	private void sound_stop() {
		if (recorder == null) {
			return;
		}
		try {
			recorder.stop();
		} catch (IllegalStateException e) { /* whatever */ }
		recorder.release();
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
				int n = recorder.read(bufs[bufs_w], 0, BUFSZ);
				if (n != BUFSZ) {
					break;
				}
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
