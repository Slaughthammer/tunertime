package org.galexander.tunertime;

import android.Manifest;
import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.LightingColorFilter;
import android.net.Uri;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuItem;
import android.view.MotionEvent;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import android.widget.Button;
import android.os.PowerManager;
import android.app.Activity;
import androidx.core.content.ContextCompat;
import androidx.core.app.ActivityCompat;
import java.text.DecimalFormat;


public class TunerTime extends Activity
		implements SurfaceHolder.Callback,
			   ActivityCompat.OnRequestPermissionsResultCallback {
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
	private View preset_buttons;
	private TextView preset_semi_display;
	private TextView preset_oct_display;
	private View fft_buttons;
	private TextView N_display;
	private Button grid_button;
	private View strobe_buttons;
	private Button note_button, afreq_button, stretch_button, preset_button;
	private View strobe_mode_buttons;
	private View fft_mode_buttons;
	private Button tone_button;

	private PowerManager.WakeLock wakelock = null;

		/* cents values are in tenths of a cent */
	private static final int U = preset.UNITS_PER_CENT;

	private Strobe strobe = null;
	private SurfaceHolder holder = null;
	private int width = 0;
	private int height = 0;

	public void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);

		parms.init(this);
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
				incr_cents(700*U);
				return true;
			}
		});
		((Button)note_buttons.findViewById(R.id.semi_minus))
			.setOnLongClickListener(new View.OnLongClickListener() {
			@Override
			public boolean onLongClick(View v) {
				incr_cents(-700*U);
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

		preset_buttons = fl8r.inflate(
				R.layout.preset_buttons, buttons_holder, false);
		preset_semi_display = (TextView)preset_buttons.findViewById(
				R.id.preset_semi_display);
		preset_oct_display = (TextView)preset_buttons.findViewById(
				R.id.preset_oct_display);

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
		preset_button = (Button)strobe_buttons.findViewById(
				R.id.preset_button);

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

	public void onStart() {
		super.onStart();
		request_permission();
	}
	public void onResume() {
		wakelock.acquire();
		stop_strobe();
		parms.load();
		if (parms.fft_mode) {
			set_mode(fft_buttons, true);
		} else {
			set_mode((parms.preset_mode ? preset_buttons
						    : note_buttons), false);
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
		parms.save();
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
		String title = null;
		if (fft_mode_ == false) {
			darken_mode(note_button);
			darken_mode(afreq_button);
			darken_mode(stretch_button);
			darken_mode(preset_button);
			if (buttons == note_buttons) {
				brighten_mode(note_button);
			} else if (buttons == afreq_buttons) {
				brighten_mode(afreq_button);
			} else if (buttons == stretch_buttons) {
				brighten_mode(stretch_button);
			} else if (buttons == preset_buttons) {
				brighten_mode(preset_button);
			}
			boolean was_preset = parms.preset_mode;
			parms.preset_mode = (buttons == preset_buttons);
			if (was_preset && !parms.preset_mode) {
				/* carry the preset note over to note mode */
				parms.note_cents = parms.strobe_cents -
					parms.temper_offset(parms.strobe_cents);
			}
			if (parms.preset_mode) {
				preset p = preset.find(parms.preset_id);
				if (p == null) {
					parms.preset_note = 0;
					parms.preset_oct = 0;
					if (preset.presets.size() >= 1) {
						p = preset.presets.get(0);
						parms.preset_id = p.id;
					}
				}
				title = "TunerTime: strobe (" +
					((p != null) ? p.name : "...") + ")";
			} else {
				title = "TunerTime: strobe";
			}
		} else {
			title = "TunerTime: FFT";
			parms.fft_xoffset = Strobe.FFT_RANGE_MULT;
		}
		buttons_holder.removeAllViews();
		buttons_holder.addView(buttons);
		parms.fft_mode = fft_mode_;
		surface_frame.removeView(strobe_buttons);
		if (!parms.fft_mode) {
			surface_frame.addView(strobe_buttons);
		}
		mode_holder.removeAllViews();
		mode_holder.addView(parms.fft_mode ? fft_mode_buttons
						   : strobe_mode_buttons);
		parms.changed = true;
		setTitle(title);
		if (parms.fft_mode) ToneGenerator.stop_tone();

		update_status();
	}


	public void do_mode(View v) {
		if (parms.fft_mode) {
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
				cents_to_freq(parms.strobe_cents, 1));
		}
		update_status();
	}

	private void incr_cents(int delta) {
		parms.note_cents += delta;
		parms.note_cents = range_limit(parms.note_cents, 0, 9600*U);
		update_status();
	}

	public void semi_incr(View v) { incr_cents(  100*U); }
	public void semi_decr(View v) { incr_cents( -100*U); }
	public void oct_incr(View v)  { incr_cents( 1200*U); }
	public void oct_decr(View v)  { incr_cents(-1200*U); }
	public void cent_incr(View v) { incr_cents(    1*U); }
	public void cent_decr(View v) { incr_cents(   -1*U); }

	public void A_incrp1(View v) {
		parms.A_freq++;
		update_status();
	}
	public void A_incr1(View v) {
		parms.A_freq+=10;
		update_status();
	}
	public void A_decrp1(View v) {
		parms.A_freq--;
		update_status();
	}
	public void A_decr1(View v) {
		parms.A_freq-=10;
		update_status();
	}
	public void reset_afreq(View v) {
		parms.A_freq = 4400;
		update_status();
	}

	public void stretch_incr(View v) {
		parms.cents_per_octave++;
		update_status();
	}
	public void stretch_decr(View v) {
		parms.cents_per_octave--;
		update_status();
	}
	public void reset_stretch(View v) {
		parms.cents_per_octave = 0;
		update_status();
	}

	public void preset_incr(View v) {
		parms.preset_note++;
		update_status();
	}

	public void preset_decr(View v) {
		parms.preset_note--;
		update_status();
	}

	public void preset_oct_incr(View v) {
		parms.preset_oct++;
		update_status();
	}

	public void preset_oct_decr(View v) {
		parms.preset_oct--;
		update_status();
	}

	public void do_preset_select(View v) {
		startActivity(new Intent(this, PresetSelectActivity.class));
	}
	public void do_preset_edit(View v) {
		startActivity(new Intent(this, PresetEditActivity.class));
	}

	public void N_incr(View v) {
		parms.fft_size *= 2;
		update_status();
	}
	public void N_decr(View v) {
		parms.fft_size /= 2;
		update_status();
	}
	public void grid_toggle(View v) {
		parms.fft_grid_freq = !parms.fft_grid_freq;
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
	public void preset_clicked(View v) {
		set_mode(preset_buttons, false);
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

	private void update_preset() {
		preset p = preset.find(parms.preset_id);
		if ((p == null) || (p.notes.size() == 0)) {
			parms.preset_note = 0;
			parms.preset_oct = 0;
			parms.strobe_cents = 4800*U;
		} else {
			int n = p.notes.size();
			while (parms.preset_note < 0) {
				parms.preset_note += n;
			}
			parms.preset_note %= n;
			parms.strobe_cents = p.notes.get(parms.preset_note) +
					1200*U*parms.preset_oct;
			while (parms.strobe_cents < 0) {
				parms.strobe_cents += 1200*U;
				parms.preset_oct++;
			}
			while (parms.strobe_cents > 9600*U) {
				parms.strobe_cents -= 1200*U;
				parms.preset_oct--;
			}
		}
	}

		/* note that ranges are checked in update_status() as well,
		 * because status display is really the only part that will go
		 * wonkers if the values go out-of-range anyways.  */
	private void update_status() {
		String temper_str = null;
		if (parms.preset_mode) {
			update_preset();
		} else {
			/* note mode: apply the selected preset if it is a
			 * temperament (12 notes, one per pitch class) */
			parms.note_cents = range_limit(parms.note_cents,
							0, 9600*U);
			int off = 0;
			if (parms.temper_offsets() != null) {
				off = parms.temper_offset(parms.note_cents);
				temper_str = preset.find(parms.preset_id).name +
					" " + note.cents_str(off);
			}
			parms.strobe_cents = parms.note_cents + off;
		}
		parms.strobe_cents = range_limit(parms.strobe_cents, 0, 9600*U);
		parms.A_freq = range_limit(parms.A_freq, 2200, 8800);

		parms.fft_size = range_limit(parms.fft_size, 512, 32768);
		parms.fft_width = range_limit(parms.fft_width, 128, Strobe.FFT_RANGE_MULT);
		parms.fft_height = range_limit(parms.fft_height, 128, Strobe.FFT_RANGE_MULT);
		if ((strobe != null) && !strobe.paused) {
			parms.fft_xoffset = Strobe.FFT_RANGE_MULT;
		}
		parms.fft_xoffset = range_limit(parms.fft_xoffset, 0,
				Strobe.FFT_RANGE_MULT-parms.fft_width);
		parms.fft_yoffset = range_limit(parms.fft_yoffset, 0,
				Strobe.FFT_RANGE_MULT-parms.fft_height);

		parms.changed = true;
		ToneGenerator.set_freq(cents_to_freq(parms.strobe_cents, 1));

				/* strobe_cents is based on A, adj_cents on C */
		note n = new note(parms.strobe_cents);
		String oct_str = Integer.toString(n.octave);

				/* note mode shows the position before the
				 * temperament, which is shown in the title */
		note nn = new note(parms.note_cents);
		note_semi_display.setText(nn.note_str);
		note_oct_display.setText(Integer.toString(nn.octave));
		note_cent_display.setText(nn.cents_str);
		if (!parms.fft_mode && !parms.preset_mode) {
			setTitle("TunerTime: strobe" + ((temper_str != null) ?
					(" (" + temper_str + ")") : ""));
		}

		preset_semi_display.setText(n.note_str+" "+n.cents_str);
		preset_oct_display.setText(oct_str);

		String center_str = string_int10(parms.A_freq);
		center_display.setText("A="+center_str);
		afreq_center_display.setText(center_str);

		String stretch_str = ((parms.cents_per_octave >= 0) ? "+" : "-") +
				string_int10(Math.abs(parms.cents_per_octave));
		stretch_display.setText(stretch_str);
		stretch_stretch_display.setText(stretch_str);

		N_display.setText(Integer.toString(parms.fft_size));
		grid_button.setText(parms.fft_grid_freq ? "Hz" : "scale");

		if (ToneGenerator.playing) {
			tone_button.getBackground().clearColorFilter();
		} else {
			tone_button.getBackground().setColorFilter(
				new LightingColorFilter(0x404040, 0));
		}
	}

		/* cents in tenths of a cent (preset.UNITS_PER_CENT), 48000=A4.
		 * also called from the native side */
	public double cents_to_freq(int cents, int harm_mult) {
		double c = ((double)(cents-4800*U)) / (double)U;
		c += 1200.0d * Math.log((double)harm_mult) / Math.log(2.0d);
		double frac = ((double)parms.cents_per_octave)/12000.0d;
		double octpow = c*(1.0d+frac) / 1200.0d;
		double mult = Math.pow(2.0d, octpow);
		return ((double)parms.A_freq)*mult/10.0d;
	}

	private void start_strobe() {
		stop_strobe();
		if (!check_permission()) {
			return;
		}
		strobe = new Strobe(this);
		strobe.setSurface(holder, width, height);
		parms.changed = true;
		strobe.start();
	}

	private void stop_strobe() {
		if (strobe == null) {
			return;
		}
		strobe.interrupt();
		strobe = null;
	}

	public void onRequestPermissionsResult(int code, String[] perms,
			int[] results) {
		if ((results.length < 1) ||
		    (results[0] != PackageManager.PERMISSION_GRANTED)) {
			new AlertDialog.Builder(this)
			.setCancelable(false)
			.setPositiveButton("OK",
				new DialogInterface.OnClickListener() {
				public void onClick(DialogInterface di,
						int which) { finish(); } })
			.setIcon(android.R.drawable.ic_dialog_alert)
			.setTitle("Oh no!")
			.setMessage("TunerTime requires access to the microphone for all of its features.  You can override the permission in system settings -> apps & notifications -> advanced -> permission manager.")
			.show();
		} else {
			if (holder != null) {
				start_strobe();
			}
		}
	}
	private boolean check_permission() {
		return (ContextCompat.checkSelfPermission(this,
					Manifest.permission.RECORD_AUDIO) ==
				PackageManager.PERMISSION_GRANTED);
	}
	private void request_permission() {
		if (!check_permission()) {
			ActivityCompat.requestPermissions(this, new String[] {
					Manifest.permission.RECORD_AUDIO }, 0);
		}
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
		private int start_x = -1, start_y = -1;
		private int start_fft_xoffset = 0, start_fft_yoffset = 0;
		private int start_x_width = 0, start_y_height = 0;
		private int start_fft_width = 0, start_fft_height = 0;
		private boolean doing_x = false, doing_y = false;
		private boolean could_be_click = false;
		public boolean onTouch(View v, MotionEvent event) {
			int i;
			int x1 = -1, x2 = -1;
			int y1 = -1, y2 = -1;
			int width = v.getWidth();
			int height = v.getHeight();

			if ((width == 0) || (height == 0)) {
				/* unlikely, but don't divide by zero */
				return false;
			}

			if ((i = event.findPointerIndex(0)) != -1) {
				x1 = (int)event.getX(i);
				y1 = (int)event.getY(i);
			} else {
				start_x_width = 0;
				start_y_height = 0;
				return false;
			}
			if ((i = event.findPointerIndex(1)) != -1) {
				x2 = (int)event.getX(i);
				y2 = (int)event.getY(i);
				could_be_click = false;
			} else {
				start_x_width = 0;
				start_y_height = 0;
			}
			if ((Math.abs(x1 - start_x) > (width/20)) && !doing_y) {
				doing_x = true;
				could_be_click = false;
			}
			if ((Math.abs(y1 - start_y) > (height/20)) && !doing_x) {
				doing_y = true;
				could_be_click = false;
			}
			switch (event.getAction()) {
			case MotionEvent.ACTION_DOWN:
				start_x = x1; start_x_width = 0;
				start_y = y1; start_y_height = 0;
				start_fft_xoffset = parms.fft_xoffset +
					((x1*parms.fft_width) / width);
				start_fft_yoffset = parms.fft_yoffset +
					((y1*parms.fft_height) / height);
				could_be_click = true;
				doing_x = false; doing_y = false;
				return true;
			case MotionEvent.ACTION_POINTER_2_DOWN:
				start_x_width = Math.abs(x1-x2);
				if (start_x_width < 10) start_x_width = 10;
				start_fft_width = parms.fft_width;
				start_y_height = Math.abs(y1-y2);
				if (start_y_height < 10) start_y_height = 10;
				start_fft_height = parms.fft_height;
				return true;
			case MotionEvent.ACTION_MOVE:
				if (!parms.fft_mode) {
					return true;
				}
				if (doing_x) {
					if (start_x_width != 0) {
						int new_width = Math.abs(x1-x2);
						if (new_width == 0) {
							new_width = 1;
						}
						parms.fft_width = (start_fft_width *
							start_x_width) / new_width;
					}
					parms.fft_xoffset = start_fft_xoffset -
						((x1*parms.fft_width) / width);
				}
				if (doing_y) {
					if (start_y_height != 0) {
						int new_height = Math.abs(y1-y2);
						if (new_height == 0) {
							new_height = 1;
						}
						parms.fft_height = (start_fft_height *
							start_y_height) / new_height;
					}
					parms.fft_yoffset = start_fft_yoffset -
						((y1*parms.fft_height) / height);
				}
				update_status();
				return true;
			case MotionEvent.ACTION_UP:
				if (could_be_click && (strobe != null)) {
					strobe.toggle_pause();
					update_status();
				}
				return true;
			}
			return false;
		}
	}

	public boolean onCreateOptionsMenu(Menu menu) {
		getMenuInflater().inflate(R.menu.option, menu);
		return super.onCreateOptionsMenu(menu);
	}

	public boolean onOptionsItemSelected(MenuItem item) {
		int id = item.getItemId();
		if (id == R.id.action_settings) {
			startActivity(new Intent(this, Settings.class));
			return true;
		} else if (id == R.id.action_help) {
			do_help();
			return true;
		} else if (id == R.id.action_about) {
			do_about();
			return true;
		}
		return super.onOptionsItemSelected(item);
	}
}
