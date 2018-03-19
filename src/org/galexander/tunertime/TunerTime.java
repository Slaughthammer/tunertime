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

	private static final String NOTE_NAMES[] = new String[] { "C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B" };

	public void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);

		Parms.init(this);
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
		((Button)note_buttons.findViewById(R.id.semi_plus))
			.setOnLongClickListener(new View.OnLongClickListener() {
			@Override
			public boolean onLongClick(View v) {
				incr_cents(700, /*temper=*/true);
				return true;
			}
		});
		((Button)note_buttons.findViewById(R.id.semi_minus))
			.setOnLongClickListener(new View.OnLongClickListener() {
			@Override
			public boolean onLongClick(View v) {
				incr_cents(-700, /*temper=*/true);
				return true;
			}
		});

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
		Parms.load();
		if (Parms.fft_mode) {
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
		if (wakelock.isHeld()) {
			wakelock.release();
		}
		stop_strobe();
		ToneGenerator.stop_tone();
		Parms.save();
		super.onPause();
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
		Parms.fft_mode = fft_mode_;
		surface_frame.removeView(strobe_buttons);
		if (!Parms.fft_mode) {
			surface_frame.addView(strobe_buttons);
		}
		mode_holder.removeAllViews();
		mode_holder.addView(Parms.fft_mode ? fft_mode_buttons
						   : strobe_mode_buttons);
		Parms.changed = true;
		setTitle(Parms.fft_mode ? "TunerTime: FFT" : "TunerTime: strobe");
		if (Parms.fft_mode) ToneGenerator.stop_tone();

		update_status();
	}


	public void do_mode(View v) {
		if (Parms.fft_mode) {
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
				cents_to_freq(Parms.strobe_cents, 1));
		}
		update_status();
	}

	private void apply_temper() {
		if (!Parms.temper_enabled || (Parms.strobe_cents < 0)) {
			return;
		}

		int note_index = ((Parms.strobe_cents+50)/100)%12;
		int octave = (Parms.strobe_cents+50)/1200;
		int cents = Parms.temper[note_index];
		Parms.strobe_cents = octave*1200 + note_index*100 + cents;
	}

	private boolean in_temper() {
		int note_index = ((Parms.strobe_cents+50)/100)%12;
		int octave = (Parms.strobe_cents+50)/1200;
		int cents = Parms.temper[note_index];
		return (Parms.strobe_cents == octave*1200 + note_index*100 + cents);
	}

	private void incr_cents(int delta, boolean temper) {
		Parms.strobe_cents += delta;
		Parms.strobe_cents = range_limit(Parms.strobe_cents, 0, 9600);
		if (temper) {
			apply_temper();
		}
		update_status();
	}

	public void semi_incr(View v) { incr_cents(  100, /*temper=*/true ); }
	public void semi_decr(View v) { incr_cents( -100, /*temper=*/true ); }
	public void oct_incr(View v)  { incr_cents( 1200, /*temper=*/true ); }
	public void oct_decr(View v)  { incr_cents(-1200, /*temper=*/true ); }
	public void cent_incr(View v) { incr_cents(    1, /*temper=*/false); }
	public void cent_decr(View v) { incr_cents(   -1, /*temper=*/false); }

	public void A_incrp1(View v) {
		Parms.A_freq++;
		update_status();
	}
	public void A_incr1(View v) {
		Parms.A_freq+=10;
		update_status();
	}
	public void A_decrp1(View v) {
		Parms.A_freq--;
		update_status();
	}
	public void A_decr1(View v) {
		Parms.A_freq-=10;
		update_status();
	}
	public void reset_afreq(View v) {
		Parms.A_freq = 4400;
		update_status();
	}

	public void stretch_incr(View v) {
		Parms.cents_per_octave++;
		update_status();
	}
	public void stretch_decr(View v) {
		Parms.cents_per_octave--;
		update_status();
	}
	public void reset_stretch(View v) {
		Parms.cents_per_octave = 0;
		update_status();
	}

	public void do_temper_save(View v) {
		int note_index = ((Parms.strobe_cents+50)/100)%12;
		int cents = (((Parms.strobe_cents%100)+50)%100)-50;
		Parms.temper[note_index] = cents;
		update_status();
	}
	public void do_temper_enable(View v) {
		Parms.temper_enabled = !Parms.temper_enabled;
		update_status();
	}

	public void harm1_incr(View v) {
		Parms.harm1_mult++;
		update_status();
	}
	public void harm1_decr(View v) {
		Parms.harm1_mult--;
		update_status();
	}
	public void harm2_incr(View v) {
		Parms.harm2_mult++;
		update_status();
	}
	public void harm2_decr(View v) {
		Parms.harm2_mult--;
		update_status();
	}
	public void reset_harm(View v) {
		Parms.harm1_mult = 2;
		Parms.harm2_mult = 4;
		update_status();
	}

	public void N_incr(View v) {
		Parms.fft_size *= 2;
		update_status();
	}
	public void N_decr(View v) {
		Parms.fft_size /= 2;
		update_status();
	}
	public void grid_toggle(View v) {
		Parms.fft_grid_freq = !Parms.fft_grid_freq;
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
		apply_temper();
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
		Parms.strobe_cents = range_limit(Parms.strobe_cents, 0, 9600);
		Parms.A_freq = range_limit(Parms.A_freq, 2200, 8800);
		Parms.harm1_mult = range_limit(Parms.harm1_mult, 1, 20);
		Parms.harm2_mult = range_limit(Parms.harm2_mult, 1, 20);

		Parms.fft_size = range_limit(Parms.fft_size, 512, 32768);
		Parms.fft_width = range_limit(Parms.fft_width, 512, Strobe.FFT_RANGE_MULT);
		Parms.fft_offset = range_limit(Parms.fft_offset, 0,
				Strobe.FFT_RANGE_MULT-Parms.fft_width);

		Parms.changed = true;
		ToneGenerator.set_freq(cents_to_freq(Parms.strobe_cents, 1));

				/* strobe_cents is based on A, adj_cents on C */
		int adj_cents = Parms.strobe_cents+900;
		int note_index = ((adj_cents+50)/100)%12;
		int octave = (adj_cents+50)/1200;
		int cents = (((adj_cents%100)+50)%100)-50;
		String note_name = NOTE_NAMES[note_index];
		DecimalFormat cents_fmt = new DecimalFormat("+00c;-00c");
		String cents_str = cents_fmt.format(cents);

		note_semi_display.setText(note_name);
		note_oct_display.setText(Integer.toString(octave));
		note_cent_display.setText(cents_str);

		temper_semi_display.setText(note_name);
		temper_cent_display.setText(cents_str);
		if (in_temper()) {
			temper_save_button.getBackground().setColorFilter(
				new LightingColorFilter(0xff888888, 0));
		} else {
			temper_save_button.getBackground().clearColorFilter();
		}
		if (Parms.temper_enabled) {
			temper_enable_button.getBackground().clearColorFilter();
			temper_enable_button.setText("disable");
		} else {
			temper_enable_button.getBackground().setColorFilter(
				new LightingColorFilter(0xff888888, 0));
			temper_enable_button.setText("enable");
		}

		String center_str = string_int10(Parms.A_freq);
		center_display.setText("A="+center_str);
		afreq_center_display.setText(center_str);

		String stretch_str = ((Parms.cents_per_octave >= 0) ? "+" : "-") +
				string_int10(Math.abs(Parms.cents_per_octave));
		stretch_display.setText(stretch_str);
		stretch_stretch_display.setText(stretch_str);

		harm1_display.setText(Integer.toString(Parms.harm1_mult));
		harm2_display.setText(Integer.toString(Parms.harm2_mult));

		N_display.setText(Integer.toString(Parms.fft_size));
		grid_button.setText(Parms.fft_grid_freq ? "Hz" : "scale");

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
		double frac = ((double)Parms.cents_per_octave)/12000.0d;
		double octpow = c*(1.0d+frac) / 1200.0d;
		double mult = Math.pow(2.0d, octpow);
		return ((double)Parms.A_freq)*mult/10.0d;
	}

	private void start_strobe() {
		stop_strobe();
		strobe = new Strobe(this);
		strobe.setSurface(holder, width, height);
		Parms.changed = true;
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
				start_fft_offset = Parms.fft_offset +
					((x1*Parms.fft_width) / wid);
				could_be_click = true;
				return true;
			case MotionEvent.ACTION_POINTER_2_DOWN:
				start_x_width = Math.abs(x1-x2);
				if (start_x_width < 10) start_x_width = 10;
				start_fft_width = Parms.fft_width;
				return true;
			case MotionEvent.ACTION_MOVE:
				if (could_be_click || !Parms.fft_mode) {
					return true;
				}
				int delta_x = x1 - start_x;
				if (start_x_width != 0) {
					int new_width = Math.abs(x1-x2);
					if (new_width == 0) {
						new_width = 1;
					}
					Parms.fft_width = (start_fft_width *
						start_x_width) / new_width;
				}
				Parms.fft_offset = start_fft_offset -
					((x1*Parms.fft_width) / wid);
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
		case R.id.action_settings:
			startActivity(new Intent(this, Settings.class));
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
