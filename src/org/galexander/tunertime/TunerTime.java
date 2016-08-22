package org.galexander.tunertime;

import android.app.Activity;
import android.os.Bundle;
import android.content.*;
import android.view.*;
import android.graphics.*;
import android.widget.TextView;
import android.widget.Button;
import android.app.AlertDialog;
import java.text.DecimalFormat;
import android.net.Uri;
import android.os.PowerManager;

public class TunerTime extends Activity implements SurfaceHolder.Callback {
	private int A_freq = 4400;		/* 440.0 *10 */
	private int cents_per_octave = 0;	/* *10 */
	private int strobe_cents = 4800;
	private int harm1_mult = 2;
	private int harm2_mult = 4;
	private int temper[] = new int[12];
	private boolean temper_enabled;

	private boolean fft_mode;
	private int fft_size;
	private int fft_width;
	private int fft_offset;
	private boolean fft_grid_freq;

	private SurfaceView w_strobe;
	private ViewGroup buttons_holder;
	private ViewGroup surface_frame;
	private ViewGroup mode_holder;
	private TextView center_display;
	private TextView stretch_display;
	private View note_buttons;
	private TextView note_semi_display;
	private TextView note_oct_display;
	private TextView note_cent_display;
	private View afreq_buttons;
	private TextView afreq_center_display;
	private View stretch_buttons;
	private TextView stretch_stretch_display;
	private View temper_buttons;
	private TextView temper_semi_display;
	private TextView temper_cent_display;
	private TextView temper_save_button;
	private TextView temper_enable_button;
	private View harm_buttons;
	private TextView harm1_display;
	private TextView harm2_display;
	private View fft_buttons;
	private TextView N_display;
	private Button grid_button;
	private View strobe_buttons;
	private Button note_button, afreq_button, stretch_button,
			temper_button, harm_button;
	private View strobe_mode_buttons;
	private View fft_mode_buttons;
	private Button tone_button;

	private PowerManager.WakeLock wakelock = null;

	private Strobe strobe = null;
	private SurfaceHolder holder = null;
	private int width = 0;
	private int height = 0;

	private static final String NOTE_NAMES[] = new String[] { "A", "A#", "B", "C", "C#", "D", "D#", "E", "F", "F#", "G", "G#" };

	public void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);

		setContentView(R.layout.main);
		w_strobe = (SurfaceView)findViewById(R.id.strobe);
		w_strobe.getHolder().addCallback(this);
		w_strobe.setOnTouchListener(new strobe_listener());
		buttons_holder = (ViewGroup)findViewById(R.id.buttons_holder);
		surface_frame = (ViewGroup)findViewById(R.id.surface_frame);
		mode_holder = (ViewGroup)findViewById(R.id.mode_holder);
		center_display = (TextView)findViewById(R.id.center_display);
		stretch_display = (TextView)findViewById(R.id.stretch_display);

		LayoutInflater fl8r = (LayoutInflater)getSystemService(
				Context.LAYOUT_INFLATER_SERVICE);
		note_buttons = fl8r.inflate(
				R.layout.note_buttons, buttons_holder, false);
		note_semi_display = (TextView)note_buttons.findViewById(
				R.id.semi_display);
		note_oct_display = (TextView)note_buttons.findViewById(
				R.id.oct_display);
		note_cent_display = (TextView)note_buttons.findViewById(
				R.id.cent_display);

		afreq_buttons = fl8r.inflate(
				R.layout.afreq_buttons, buttons_holder, false);
		afreq_center_display = (TextView)afreq_buttons.findViewById(
				R.id.center_display2);

		stretch_buttons = fl8r.inflate(
				R.layout.stretch_buttons, buttons_holder,false);
		stretch_stretch_display = (TextView)stretch_buttons.
				findViewById(R.id.stretch_display2);

		temper_buttons = fl8r.inflate(
				R.layout.temper_buttons, buttons_holder, false);
		temper_semi_display = (TextView)temper_buttons.findViewById(
				R.id.semi_display2);
		temper_cent_display = (TextView)temper_buttons.findViewById(
				R.id.cent_display2);
		temper_save_button = (Button)temper_buttons.findViewById(
				R.id.temper_save);
		temper_enable_button = (Button)temper_buttons.findViewById(
				R.id.temper_enable);

		harm_buttons = fl8r.inflate(
				R.layout.harm_buttons, buttons_holder, false);
		harm1_display = (TextView)harm_buttons.findViewById(
				R.id.harm1_display);
		harm2_display = (TextView)harm_buttons.findViewById(
				R.id.harm2_display);

		fft_buttons = fl8r.inflate(
				R.layout.fft_buttons, buttons_holder, false);
		N_display = (TextView)fft_buttons.findViewById(
				R.id.N_display);
		grid_button = (Button)fft_buttons.findViewById(
				R.id.grid_button);

		strobe_buttons = fl8r.inflate(
				R.layout.strobe_buttons, surface_frame, false);
		note_button = (Button)strobe_buttons.findViewById(
				R.id.note_button);
		afreq_button = (Button)strobe_buttons.findViewById(
				R.id.afreq_button);
		stretch_button = (Button)strobe_buttons.findViewById(
				R.id.stretch_button);
		temper_button = (Button)strobe_buttons.findViewById(
				R.id.temper_button);
		harm_button = (Button)strobe_buttons.findViewById(
				R.id.harm_button);

		PowerManager pm = (PowerManager)getSystemService(
						Context.POWER_SERVICE);
		wakelock = pm.newWakeLock(PowerManager.SCREEN_BRIGHT_WAKE_LOCK,
						"TunerTime");

		strobe_mode_buttons = fl8r.inflate(
				R.layout.strobe_mode, mode_holder, false);
		tone_button = (Button)strobe_mode_buttons.findViewById(
				R.id.tone_button);
		fft_mode_buttons = fl8r.inflate(
				R.layout.fft_mode, mode_holder, false);
	}

	public void onResume() {
		wakelock.acquire();
		stop_strobe();
		load_config();
		if (fft_mode) {
			set_mode(fft_buttons, true);
		} else {
			set_mode(note_buttons, false);
		}
		update_status();
		if (holder != null) {
			/* lingering surface must still be valid */
			start_strobe();
		}
		super.onResume();
	}

	public void onPause() {
		wakelock.release();
		stop_strobe();
		ToneGenerator.stop_tone();
		save_config();
		super.onPause();
	}

	private void load_config() {
		SharedPreferences prefs = getPreferences(Context.MODE_PRIVATE);
		A_freq = prefs.getInt("A_freq", 4400);
		if (A_freq <= 880) {
			/* the setting must come from before we encoded A_freq
			 * as *10 (so there is one decimal point of info) */
			A_freq *= 10;
		}
		cents_per_octave = prefs.getInt("cents_per_octave", 0);
		if (cents_per_octave == 0) {
			/* use older config option if available.
			 * see derivation in NOTES Aug 21, 2016 */
			int octave_stretch = prefs.getInt("octave_stretch", 0);
			if (octave_stretch != 0) {
				double os = ((double)octave_stretch)*0.00001d;
				double lg = Math.log(2.0d+os)/Math.log(2.0d);
				double t = (lg-1.0d)*12000.0d;
				cents_per_octave = (int)Math.round(t);
			}
		}
		strobe_cents = prefs.getInt("strobe_cents", 4800);
		harm1_mult = prefs.getInt("harm1_mult", 2);
		harm2_mult = prefs.getInt("harm2_mult", 4);
		temper[0] = prefs.getInt("temper0", 0);
		temper[1] = prefs.getInt("temper1", 0);
		temper[2] = prefs.getInt("temper2", 0);
		temper[3] = prefs.getInt("temper3", 0);
		temper[4] = prefs.getInt("temper4", 0);
		temper[5] = prefs.getInt("temper5", 0);
		temper[6] = prefs.getInt("temper6", 0);
		temper[7] = prefs.getInt("temper7", 0);
		temper[8] = prefs.getInt("temper8", 0);
		temper[9] = prefs.getInt("temper9", 0);
		temper[10] = prefs.getInt("temper10", 0);
		temper[11] = prefs.getInt("temper11", 0);
		temper_enabled = prefs.getBoolean("temper_enabled", false);
		fft_mode = prefs.getBoolean("fft_mode", false);
		fft_size = prefs.getInt("fft_size", 2048);
		fft_width = prefs.getInt("fft_width", Strobe.FFT_RANGE_MULT/4);
		fft_offset = prefs.getInt("fft_offset", 0);
		fft_grid_freq = prefs.getBoolean("fft_grid_freq", false);
	}

	private void save_config() {
		SharedPreferences prefs = getPreferences(Context.MODE_PRIVATE);
		SharedPreferences.Editor editor = prefs.edit();
		editor.putInt("A_freq", A_freq);
		editor.putInt("cents_per_octave", cents_per_octave);
		editor.remove("octave_stretch");	/* obsolete */
		editor.putInt("strobe_cents", strobe_cents);
		editor.putInt("harm1_mult", harm1_mult);
		editor.putInt("harm2_mult", harm2_mult);
		editor.putInt("temper0", temper[0]);
		editor.putInt("temper1", temper[1]);
		editor.putInt("temper2", temper[2]);
		editor.putInt("temper3", temper[3]);
		editor.putInt("temper4", temper[4]);
		editor.putInt("temper5", temper[5]);
		editor.putInt("temper6", temper[6]);
		editor.putInt("temper7", temper[7]);
		editor.putInt("temper8", temper[8]);
		editor.putInt("temper9", temper[9]);
		editor.putInt("temper10", temper[10]);
		editor.putInt("temper11", temper[11]);
		editor.putBoolean("temper_enabled", temper_enabled);
		editor.putBoolean("fft_mode", fft_mode);
		editor.putInt("fft_size", fft_size);
		editor.putInt("fft_width", fft_width);
		editor.putInt("fft_offset", fft_offset);
		editor.putBoolean("fft_grid_freq", fft_grid_freq);
		editor.commit();
	}

	private void darken_mode(Button b) {
		b.getBackground().setColorFilter(
                                new LightingColorFilter(0x88888888, 0));
	}
	private void brighten_mode(Button b) {
		b.getBackground().clearColorFilter();
	}
	private void set_mode(View buttons, boolean fft_mode_) {
		if (fft_mode_ == false) {
			darken_mode(note_button);
			darken_mode(afreq_button);
			darken_mode(stretch_button);
			darken_mode(temper_button);
			darken_mode(harm_button);
			if (buttons == note_buttons) {
				brighten_mode(note_button);
			} else if (buttons == afreq_buttons) {
				brighten_mode(afreq_button);
			} else if (buttons == stretch_buttons) {
				brighten_mode(stretch_button);
			} else if (buttons == temper_buttons) {
				brighten_mode(temper_button);
			} else if (buttons == harm_buttons) {
				brighten_mode(harm_button);
			}
		}
		buttons_holder.removeAllViews();
		buttons_holder.addView(buttons);
		fft_mode = fft_mode_;
		surface_frame.removeView(strobe_buttons);
		if (!fft_mode) {
			surface_frame.addView(strobe_buttons);
		}
		mode_holder.removeAllViews();
		mode_holder.addView(fft_mode ? fft_mode_buttons
					     : strobe_mode_buttons);
		if (strobe != null) {
			strobe.setFft(fft_mode);
		}
		setTitle(fft_mode ? "TunerTime: FFT" : "TunerTime: strobe");
		if (fft_mode) ToneGenerator.stop_tone();

		update_status();
	}


	public void do_mode(View v) {
		if (fft_mode) {
			set_mode(note_buttons, false);
		} else {
			set_mode(fft_buttons, true);
		}
	}

	public void do_tone(View v) {
		if (ToneGenerator.playing) {
			ToneGenerator.stop_tone();
		} else {
			ToneGenerator.start_tone(
				cents_to_freq(strobe_cents, 1));
		}
		update_status();
	}

	private void apply_temper() {
		if (!temper_enabled) {
			return;
		}

		int note_index = ((strobe_cents+50)%1200)/100;
		int octave = (strobe_cents+50)/1200;
		int cents = temper[note_index];
		strobe_cents = octave*1200 + note_index*100 + cents;
	}

	public void semi_incr(View v) {
		strobe_cents += 100;
		apply_temper();
		update_status();
	}
	public void semi_decr(View v) {
		strobe_cents -= 100;
		apply_temper();
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

	public void A_incrp1(View v) {
		A_freq++;
		update_status();
	}
	public void A_incr1(View v) {
		A_freq+=10;
		update_status();
	}
	public void A_decrp1(View v) {
		A_freq--;
		update_status();
	}
	public void A_decr1(View v) {
		A_freq-=10;
		update_status();
	}
	public void reset_afreq(View v) {
		A_freq = 4400;
		update_status();
	}

	public void stretch_incr(View v) {
		cents_per_octave++;
		update_status();
	}
	public void stretch_decr(View v) {
		cents_per_octave--;
		update_status();
	}
	public void reset_stretch(View v) {
		cents_per_octave = 0;
		update_status();
	}

	public void do_temper_save(View v) {
		int note_index = ((strobe_cents+50)%1200)/100;
		int cents = (((strobe_cents%100)+50)%100)-50;
		temper[note_index] = cents;
		update_status();
	}
	public void do_temper_enable(View v) {
		temper_enabled = !temper_enabled;
		update_status();
	}

	public void harm1_incr(View v) {
		harm1_mult++;
		update_status();
	}
	public void harm1_decr(View v) {
		harm1_mult--;
		update_status();
	}
	public void harm2_incr(View v) {
		harm2_mult++;
		update_status();
	}
	public void harm2_decr(View v) {
		harm2_mult--;
		update_status();
	}
	public void reset_harm(View v) {
		harm1_mult = 2;
		harm2_mult = 4;
		update_status();
	}

	public void N_incr(View v) {
		fft_size *= 2;
		update_status();
	}
	public void N_decr(View v) {
		fft_size /= 2;
		update_status();
	}
	public void grid_toggle(View v) {
		fft_grid_freq = !fft_grid_freq;
		update_status();
	}

	public void note_clicked(View v) {
		set_mode(note_buttons, false);
	}
	public void afreq_clicked(View v) {
		set_mode(afreq_buttons, false);
	}
	public void stretch_clicked(View v) {
		set_mode(stretch_buttons, false);
	}
	public void temper_clicked(View v) {
		set_mode(temper_buttons, false);
	}
	public void harm_clicked(View v) {
		set_mode(harm_buttons, false);
	}

	private void do_help() {
		Intent i = new Intent(Intent.ACTION_VIEW);
		i.setData(Uri.parse("http://galexander.org/software/tunertime/"));
		startActivity(i);
	}

	private String my_version() {
		try {
			return getPackageManager().
				getPackageInfo(getPackageName(), 0).versionName;
		} catch (Exception e) {
			return "UNKNOWN";
		}
	}

	private void do_about() {
		AlertDialog.Builder b = new AlertDialog.Builder(this);
		b.setCancelable(true);
		b.setPositiveButton("OK",
			new DialogInterface.OnClickListener() {
			public void onClick(DialogInterface di, int which) { }
			});
		b.setIcon(android.R.drawable.ic_dialog_info);
		b.setTitle("About");
		b.setMessage("TunerTime version " + my_version());
		b.show();
	}

	private int range_limit(int val, int lo, int hi) {
		if (val < lo) {
			return lo;
		} else if (val > hi) {
			return hi;
		}
		return val;
	}

	private static String string_int10(int n) {
		return Integer.toString(n/10) + "." + Integer.toString(n%10);
	}

		/* note that ranges are checked in update_status() as well,
		 * because status display is really the only part that will go
		 * wonkers if the values go out-of-range anyways.  */
	private void update_status() {
		strobe_cents = range_limit(strobe_cents, 0, 9600);
		A_freq = range_limit(A_freq, 2200, 8800);
		harm1_mult = range_limit(harm1_mult, 1, 20);
		harm2_mult = range_limit(harm2_mult, 1, 20);

		fft_size = range_limit(fft_size, 512, 32768);
		fft_width = range_limit(fft_width, 512, Strobe.FFT_RANGE_MULT);
		fft_offset = range_limit(fft_offset, 0,
				Strobe.FFT_RANGE_MULT-fft_width);

		if (strobe != null) {
			strobe.setParms(strobe_cents, harm1_mult, harm2_mult,
				fft_size, fft_width, fft_offset, fft_grid_freq);
		}
		ToneGenerator.set_freq(cents_to_freq(strobe_cents, 1));

		int note_index = ((strobe_cents+50)%1200)/100;
		int octave = (strobe_cents+50)/1200;
		int cents = (((strobe_cents%100)+50)%100)-50;
		String note_name = NOTE_NAMES[note_index];
		DecimalFormat cents_fmt = new DecimalFormat("+00c;-00c");
		String cents_str = cents_fmt.format(cents);

		note_semi_display.setText(note_name);
		note_oct_display.setText(Integer.toString(octave));
		note_cent_display.setText(cents_str);

		temper_semi_display.setText(note_name);
		temper_cent_display.setText(cents_str);
		if (cents == temper[note_index]) {
			temper_save_button.getBackground().setColorFilter(
				new LightingColorFilter(0xff888888, 0));
		} else {
			temper_save_button.getBackground().clearColorFilter();
		}
		if (temper_enabled) {
			temper_enable_button.getBackground().clearColorFilter();
			temper_enable_button.setText("disable");
		} else {
			temper_enable_button.getBackground().setColorFilter(
				new LightingColorFilter(0xff888888, 0));
			temper_enable_button.setText("enable");
		}

		String center_str = string_int10(A_freq);
		center_display.setText("A="+center_str);
		afreq_center_display.setText(center_str);

		String stretch_str = ((cents_per_octave >= 0) ? "+" : "-") +
				string_int10(Math.abs(cents_per_octave));
		stretch_display.setText(stretch_str);
		stretch_stretch_display.setText(stretch_str);

		harm1_display.setText(Integer.toString(harm1_mult));
		harm2_display.setText(Integer.toString(harm2_mult));

		N_display.setText(Integer.toString(fft_size));
		grid_button.setText(fft_grid_freq ? "Hz" : "scale");

		if (ToneGenerator.playing) {
			tone_button.getBackground().clearColorFilter();
		} else {
			tone_button.getBackground().setColorFilter(
				new LightingColorFilter(0xff888888, 0));
		}
	}

	public double cents_to_freq(int cents, int harm_mult) {
		double c = (double)(cents-4800);
		c += 1200.0d * Math.log((double)harm_mult) / Math.log(2.0d);
		double frac = ((double)cents_per_octave)/12000.0d;
		double octpow = c*(1.0d+frac) / 1200.0d;
		double mult = Math.pow(2.0d, octpow);
		return ((double)A_freq)*mult/10.0d;
	}

	private void start_strobe() {
		stop_strobe();
		strobe = new Strobe(this);
		strobe.setSurface(holder, width, height);
		strobe.setFft(fft_mode);
		strobe.setParms(strobe_cents, harm1_mult, harm2_mult,
			fft_size, fft_width, fft_offset, fft_grid_freq);
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

	private class strobe_listener implements View.OnTouchListener {
		private int start_x = -1;
		private int start_fft_offset = 0;
		private int start_x_width = 0;
		private int start_fft_width = 0;
		private boolean could_be_click = false;
		public boolean onTouch(View v, MotionEvent event) {
			int i;
			int x1 = -1, x2 = -1;
			int wid = v.getWidth();

			if (wid == 0) {
				/* unlikely, but don't divide by zero */
				return false;
			}

			if ((i = event.findPointerIndex(0)) != -1) {
				x1 = (int)event.getX(i);
			} else {
				start_x_width = 0;
				return false;
			}
			if ((i = event.findPointerIndex(1)) != -1) {
				x2 = (int)event.getX(i);
				could_be_click = false;
			} else {
				start_x_width = 0;
			}
			if (could_be_click &&
			    (Math.abs(x1 - start_x) > (wid/40))) {
				could_be_click = false;
			}
			switch (event.getAction()) {
			case MotionEvent.ACTION_DOWN:
				start_x = x1;
				start_x_width = 0;
				start_fft_offset = fft_offset +
					((x1*fft_width) / wid);
				could_be_click = true;
				return true;
			case MotionEvent.ACTION_POINTER_2_DOWN:
				start_x_width = Math.abs(x1-x2);
				if (start_x_width < 10) start_x_width = 10;
				start_fft_width = fft_width;
				return true;
			case MotionEvent.ACTION_MOVE:
				if (could_be_click || !fft_mode) {
					return true;
				}
				int delta_x = x1 - start_x;
				if (start_x_width != 0) {
					int new_width = Math.abs(x1-x2);
					if (new_width == 0) {
						new_width = 1;
					}
					fft_width = (start_fft_width *
						start_x_width) / new_width;
				}
				fft_offset = start_fft_offset -
					((x1*fft_width) / wid);
				update_status();
				return true;
			case MotionEvent.ACTION_UP:
				if (could_be_click && (strobe != null)) {
					strobe.toggle_pause();
				}
			}
			return false;
		}
	}

	public boolean onCreateOptionsMenu(Menu menu) {
		getMenuInflater().inflate(R.menu.option, menu);
		return super.onCreateOptionsMenu(menu);
	}

	public boolean onOptionsItemSelected(MenuItem item) {
		switch (item.getItemId()) {
		case R.id.action_strobe:
			set_mode(note_buttons, false);
			return true;
		case R.id.action_fft:
			set_mode(fft_buttons, true);
			return true;
		case R.id.action_help:
			do_help();
			return true;
		case R.id.action_about:
			do_about();
			return true;
		}
		return super.onOptionsItemSelected(item);
	}
}
