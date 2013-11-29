package org.galexander.tunertime;

import android.app.Activity;
import android.os.Bundle;
import android.content.*;
import android.view.*;
import android.graphics.*;
import android.widget.TextView;
import java.text.DecimalFormat;
import java.lang.*;

public class TunerTime extends Activity implements SurfaceHolder.Callback {
	private int A_freq = 440;
	private int octave_stretch = 0;
	private int strobe_cents = 4800;

	private SurfaceView w_strobe;
	private View w_main_buttons;
	private TextView w_semi_display;
	private TextView w_oct_display;
	private TextView w_cent_display;
	private TextView w_center_display;
	private TextView w_stretch_display;
	private View w_conf_buttons;
	private TextView w_center_display2;
	private TextView w_stretch_display2;

	private Strobe strobe = null;
	private SurfaceHolder holder = null;
	private int width = 0;
	private int height = 0;

	private static final String NOTE_NAMES[] = new String[] { "A", "A#", "B", "C", "C#", "D", "D#", "E", "F", "F#", "G", "G#" };

	public void onCreate(Bundle savedInstanceState)
	{
		super.onCreate(savedInstanceState);
		setContentView(R.layout.main);

		w_strobe = (SurfaceView)findViewById(R.id.strobe);
		w_strobe.getHolder().addCallback(this);

		w_main_buttons = findViewById(R.id.main_buttons);
		w_semi_display = (TextView)findViewById(R.id.semi_display);
		w_oct_display = (TextView)findViewById(R.id.oct_display);
		w_cent_display = (TextView)findViewById(R.id.cent_display);
		w_center_display = (TextView)findViewById(R.id.center_display);
		w_stretch_display = (TextView)findViewById(R.id.stretch_display);
		w_conf_buttons = findViewById(R.id.conf_buttons);
		w_center_display2 = (TextView)findViewById(R.id.center_display2);
		w_stretch_display2 = (TextView)findViewById(R.id.stretch_display2);

	}
	
	public void onResume() {
		load_config();
		do_note(null);
		update_status();
		if (holder != null) {
			/* lingering surface must still be valid */
			start_strobe();
		}
		super.onResume();
	}

	public void onPause() {
		stop_strobe();
		save_config();
		super.onPause();
	}

	private void load_config() {
		SharedPreferences prefs = getPreferences(Context.MODE_PRIVATE);
		A_freq = prefs.getInt("A_freq", 440);
		octave_stretch = prefs.getInt("octave_stretch", 0);
		strobe_cents = prefs.getInt("strobe_cents", 4800);
	}

	private void save_config() {
		SharedPreferences prefs = getPreferences(Context.MODE_PRIVATE);
		SharedPreferences.Editor editor = prefs.edit();
		editor.putInt("A_freq", A_freq);
		editor.putInt("octave_stretch", octave_stretch);
		editor.putInt("strobe_cents", strobe_cents);
		editor.commit();
	}

	public void semi_incr(View v) {
		strobe_cents += 100;
		update_status();
	}
	public void semi_decr(View v) {
		strobe_cents -= 100;
		update_status();
	}
	public void oct_incr(View v) {
		strobe_cents += 1200;
		update_status();
	}
	public void oct_decr(View v) {
		strobe_cents -= 1200;
		update_status();
	}
	public void cent_incr(View v) {
		strobe_cents++;
		update_status();
	}
	public void cent_decr(View v) {
		strobe_cents--;
		update_status();
	}
	public void do_conf(View v) {
		w_conf_buttons.setVisibility(View.VISIBLE);
		w_main_buttons.setVisibility(View.GONE);
	}
	public void A_incr(View v) {
		A_freq++;
		update_status();
	}
	public void A_decr(View v) {
		A_freq--;
		update_status();
	}
	public void stretch_incr(View v) {
		octave_stretch+=25;
		update_status();
	}
	public void stretch_decr(View v) {
		octave_stretch-=25;
		update_status();
	}
	public void reset_conf(View v) {
		A_freq = 440;
		octave_stretch = 0;
		update_status();
	}
	public void do_note(View v) {
		w_main_buttons.setVisibility(View.VISIBLE);
		w_conf_buttons.setVisibility(View.GONE);
	}

		/* note that ranges are checked in update_status() as well,
		 * because status display is really the only part that will go
		 * wonkers if the values go out-of-range anyways.  */
	private void update_status() {
		if (strobe_cents < 0) {
			strobe_cents = 0;
		} else if (strobe_cents > 9600) {
			strobe_cents = 9600;
		}
		int note_index = ((strobe_cents+50)%1200)/100;
		int octave = (strobe_cents+50)/1200;
		int cents = strobe_cents%100;
		if (cents >= 50) { cents -= 100; }
		w_semi_display.setText(NOTE_NAMES[note_index]);
		w_oct_display.setText(Integer.toString(octave));
		DecimalFormat cents_fmt = new DecimalFormat("+00c;-00c");
		String cents_str = cents_fmt.format(cents);
		w_cent_display.setText(cents_str);

		if (strobe != null) {
			strobe.setCents(strobe_cents);
		}

		if (A_freq < 220) {
			A_freq = 220;
		} else if (A_freq > 880) {
			A_freq = 880;
		}
		String center_str = "A="+Integer.toString(A_freq);
		w_center_display.setText(center_str);
		w_center_display2.setText(center_str);

		DecimalFormat stretch_fmt = new DecimalFormat("+00000;-00000");
		String stretch_str = stretch_fmt.format(octave_stretch);
		w_stretch_display.setText(stretch_str);
		w_stretch_display2.setText(stretch_str);
	}

	public double cents_to_freq(int cents) {
		double octbase = 2.0+(((double)octave_stretch)*0.00001);
		double octpow = ((double)(cents-4800)) / 1200.0;
		double mult = Math.pow(octbase, octpow);
		return ((double)A_freq)*mult;
	}

	private void start_strobe() {
		if (strobe != null) {
			stop_strobe();
		}
		strobe = new Strobe(this);
		strobe.setSurface(holder, width, height);
		strobe.setCents(strobe_cents);
		strobe.start();
	}

	private void stop_strobe() {
		if (strobe == null) {
			return;
		}
		strobe.interrupt();
		strobe = null;
	}

		/* interface SurfaceHolder.Callback */
	public void surfaceCreated(SurfaceHolder h) {
		/* ignore - no point starting the thread until we have
		 * dimensions */
		if (holder != null) {
			/* we've received a surfaceChanged() already, assume it
			 * is still valid */
			start_strobe();
		}
	}
	public void surfaceChanged(SurfaceHolder h, int format, int width_, int height_) {
		holder = h;
		width = width_;
		height = height_;
		if (strobe != null) {
			/* update existing thread */
			strobe.setSurface(h, width, height);
		} else {
			/* start new thread */
			start_strobe();
		}
	}
	public void surfaceDestroyed(SurfaceHolder h) {
		holder = null;
		if (strobe != null) {
			strobe.setSurface(null, 0, 0);
			stop_strobe();
		}
	}
}
