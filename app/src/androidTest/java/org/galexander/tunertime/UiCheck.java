package org.galexander.tunertime;

import android.app.Activity;
import android.app.Instrumentation;
import android.app.UiAutomation;
import android.content.Context;
import android.graphics.Insets;
import android.graphics.Rect;
import android.os.Build;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.text.Layout;
import android.util.Log;
import android.view.DisplayCutout;
import android.view.SurfaceView;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.view.inspector.WindowInspector;
import android.widget.AbsListView;
import android.widget.AdapterView;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.ScrollView;
import android.widget.TextView;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry;
import androidx.test.runner.lifecycle.Stage;
import androidx.test.uiautomator.UiDevice;
import com.google.android.apps.common.testing.accessibility.framework.AccessibilityCheckPreset;
import com.google.android.apps.common.testing.accessibility.framework.AccessibilityCheckResult.AccessibilityCheckResultType;
import com.google.android.apps.common.testing.accessibility.framework.AccessibilityHierarchyCheck;
import com.google.android.apps.common.testing.accessibility.framework.AccessibilityHierarchyCheckResult;
import com.google.android.apps.common.testing.accessibility.framework.uielement.AccessibilityHierarchyAndroid;
import com.google.android.apps.common.testing.accessibility.framework.uielement.ViewHierarchyElement;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/* Generic layout and accessibility checks for whatever window is on top.
 *
 * check(screen) walks the view hierarchy of the topmost window of the app
 * (activity, dialog or popup menu) and records a finding for every shown
 * view that matters (clickable, text, the strobe surface) that is
 *  OFFSCREEN       outside the window or the window outside the display
 *  CLIPPED         cut by a parent that can't scroll it into view
 *  UNDER_BARS      under the status or navigation bar
 *  UNDER_CUTOUT    under the camera cutout
 *  OVERLAP         overlapping another such view (not its parent/child)
 *  TOUCH_TARGET    clickable but smaller than 48x48dp
 *  NO_LABEL        clickable without text or content description
 *  TEXT_CUT_H/V    text wider/taller than the view (cut off)
 *  TEXT_ELLIPSIZED text shortened with "..."
 *  ATF_*           anything else the Accessibility Test Framework flags
 *                  (its warnings are only reported, never fail)
 *
 * A finding's key is screen|orientation|fontscale|CHECK|element, e.g.
 *  main.note|land|1.3|TEXT_CUT_V|Button"cent+"
 * Keys listed in androidTest/assets/known_ui_issues.txt (where * matches
 * any field) are known and only reported; any other finding fails the
 * test at the end (finish()), after all states have been checked.
 * Pass -e strict true to fail on known ones as well.
 *
 * Screenshots and report.txt go to /data/local/tmp/tt-ui-tests/<device>/
 * on the device (it survives the uninstall after the test run), get them
 * with: adb pull /data/local/tmp/tt-ui-tests */
final class UiCheck {
	static final String TAG = "UiCheck";
	static final String OUT_ROOT = "/data/local/tmp/tt-ui-tests";
	static final int MIN_TOUCH_DP = 48;

	final Instrumentation inst;
	final UiDevice dev;
	final Context target;
	final String device;
	final String out_dir;
	final boolean strict;
	final boolean shots;

	/* current context, set by the test */
	String orient = "port";
	String font = "1.0";

	private final List<String[]> rules = new ArrayList<String[]>();
	private final List<String> found = new ArrayList<String>();
	private final List<String> fresh = new ArrayList<String>();
	private String saved_font_scale = null;
	private final String[] anim_keys = { "window_animation_scale",
		"transition_animation_scale", "animator_duration_scale" };
	private final String[] saved_anim = new String[3];

	UiCheck() {
		inst = InstrumentationRegistry.getInstrumentation();
		dev = UiDevice.getInstance(inst);
		target = inst.getTargetContext();
		Bundle args = InstrumentationRegistry.getArguments();
		String d = args.getString("device");
		if (d == null) {
			d = Build.MANUFACTURER + "-" + Build.MODEL + "-api" +
				Build.VERSION.SDK_INT + "-" +
				target.getResources().getDisplayMetrics()
					.densityDpi + "dpi";
		}
		device = d.replaceAll("[^A-Za-z0-9._-]", "_");
		out_dir = OUT_ROOT + "/" + device;
		strict = "true".equals(args.getString("strict"));
		shots = !"false".equals(args.getString("screenshots"));
		shell("mkdir -p " + out_dir);
		new File(local_dir(), "report.txt").delete();	/* new run */
		load_rules();
	}

	/* ---- device setup ---- */

	void setup_device() {
		for (int i = 0; i < anim_keys.length; i++) {
			saved_anim[i] = shell("settings get global " +
						anim_keys[i]).trim();
			shell("settings put global " + anim_keys[i] + " 0");
		}
		saved_font_scale = shell("settings get system font_scale").trim();
	}

	void restore_device() {
		for (int i = 0; i < anim_keys.length; i++) {
			String v = saved_anim[i];
			if ((v == null) || v.equals("null") || v.isEmpty()) {
				shell("settings delete global " + anim_keys[i]);
			} else {
				shell("settings put global " + anim_keys[i] +
						" " + v);
			}
		}
		if ((saved_font_scale == null) ||
		    saved_font_scale.equals("null") ||
		    saved_font_scale.isEmpty()) {
			shell("settings put system font_scale 1.0");
		} else {
			shell("settings put system font_scale " +
					saved_font_scale);
		}
		try {
			dev.setOrientationNatural();
			dev.unfreezeRotation();
		} catch (Exception e) {
		}
	}

	void set_font_scale(String fs) {
		font = fs;
		shell("settings put system font_scale " + fs);
		settle(1500);
	}

	/* rotates the device; the activity is recreated */
	void set_orientation(String o) {
		orient = o;
		try {
			if (o.equals("land")) {
				dev.setOrientationLeft();
			} else {
				dev.setOrientationNatural();
			}
		} catch (Exception e) {
			throw new RuntimeException(e);
		}
		settle(1500);
	}

	void settle(long ms) {
		inst.waitForIdleSync();
		dev.waitForIdle(3000);
		try {
			Thread.sleep(ms);
		} catch (InterruptedException e) {
		}
		inst.waitForIdleSync();
	}

	static List<String> arg_list(String name, String def) {
		String s = InstrumentationRegistry.getArguments().getString(name);
		List<String> ret = new ArrayList<String>();
		for (String t : ((s != null) ? s : def).split(",")) {
			if (!t.trim().isEmpty()) {
				ret.add(t.trim());
			}
		}
		return ret;
	}

	Activity current_activity() {
		final Activity[] a = new Activity[1];
		for (int tries = 0; (tries < 50) && (a[0] == null); tries++) {
			inst.runOnMainSync(new Runnable() {
				public void run() {
					Collection<Activity> c =
						ActivityLifecycleMonitorRegistry
						.getInstance()
						.getActivitiesInStage(Stage.RESUMED);
					if (!c.isEmpty()) {
						a[0] = c.iterator().next();
					}
				} });
			if (a[0] == null) {
				settle(100);
			}
		}
		if (a[0] == null) {
			throw new IllegalStateException("no resumed activity");
		}
		return a[0];
	}

	void on_main(Runnable r) {
		inst.runOnMainSync(r);
		settle(300);
	}

	/* ---- shell ---- */

	String shell(String cmd) {
		UiAutomation ua = inst.getUiAutomation();
		ParcelFileDescriptor pfd = ua.executeShellCommand(cmd);
		StringBuilder sb = new StringBuilder();
		try {
			InputStream is = new ParcelFileDescriptor
					.AutoCloseInputStream(pfd);
			BufferedReader r = new BufferedReader(
					new InputStreamReader(is));
			String line;
			while ((line = r.readLine()) != null) {
				sb.append(line).append("\n");
			}
			r.close();
		} catch (IOException e) {
		}
		return sb.toString();
	}

	/* ---- known issues ---- */

	private void load_rules() {
		try {
			InputStream is = inst.getContext().getAssets()
					.open("known_ui_issues.txt");
			BufferedReader r = new BufferedReader(
					new InputStreamReader(is, "UTF-8"));
			String line;
			while ((line = r.readLine()) != null) {
				int h = line.indexOf('#');
				if (h >= 0) {
					line = line.substring(0, h);
				}
				line = line.trim();
				if (!line.isEmpty()) {
					rules.add(line.split("\\|", -1));
				}
			}
			r.close();
		} catch (IOException e) {
			Log.w(TAG, "no known_ui_issues.txt", e);
		}
	}

	private boolean known(String key) {
		String[] k = key.split("\\|", -1);
		for (String[] r : rules) {
			if (r.length != k.length) {
				continue;
			}
			boolean ok = true;
			for (int i = 0; ok && (i < k.length); i++) {
				ok = r[i].equals("*") || r[i].equals(k[i]) ||
				     (r[i].endsWith("*") && k[i].startsWith(
					r[i].substring(0, r[i].length()-1)));
			}
			if (ok) {
				return true;
			}
		}
		return false;
	}

	private void add(String screen, String check, String elem,
			String detail) {
		add(screen, check, elem, detail, false);
	}

	/* soft findings (ATF warnings) are only reported, never fail */
	private void add(String screen, String check, String elem,
			String detail, boolean soft) {
		String key = screen + "|" + orient + "|" + font + "|" +
				check + "|" + elem;
		String line = key + "\t" + detail;
		if (found.contains(line)) {
			return;
		}
		found.add(line);
		boolean k = known(key);
		if ((!k && !soft) || strict) {
			fresh.add(line);
		}
		String tag = soft ? "warn" : k ? "known" : "NEW";
		Log.w(TAG, tag + " " + device + " " + line);
		append_report(tag + "\t" + line);
	}

	private void append_report(String line) {
		File f = new File(local_dir(), "report.txt");
		try {
			FileOutputStream os = new FileOutputStream(f, true);
			os.write((line + "\n").getBytes("UTF-8"));
			os.close();
		} catch (IOException e) {
			Log.e(TAG, "report", e);
		}
	}

	private File local_dir() {
		File d = new File(target.getExternalFilesDir(null), "ui-tests");
		d.mkdirs();
		return d;
	}

	/* fails the test if anything new was found; call at the end */
	void finish(String test) {
		/* the report goes next to the screenshots, readable by adb */
		File f = new File(local_dir(), "report.txt");
		shell("cp " + f.getAbsolutePath() + " " + out_dir +
				"/report.txt");
		if (!fresh.isEmpty()) {
			StringBuilder sb = new StringBuilder();
			sb.append(test).append(" on ").append(device).append(": ")
			  .append(fresh.size())
			  .append(" new UI problem(s) (key<TAB>detail):\n");
			for (String s : fresh) {
				sb.append("  ").append(s).append("\n");
			}
			fresh.clear();
			throw new AssertionError(sb.toString());
		}
	}

	/* ---- screenshots ---- */

	void screenshot(String screen) {
		if (!shots) {
			return;
		}
		String fs = font.replace('.', '_');
		shell("screencap -p " + out_dir + "/" + screen + "-" + orient +
				"-fs" + fs + ".png");
	}

	/* ---- the checks ---- */

	/* the app's topmost window: activity, dialog or popup */
	View top_root() {
		final View[] ret = new View[1];
		inst.runOnMainSync(new Runnable() {
			public void run() {
				List<View> l = WindowInspector.getGlobalWindowViews();
				for (int i = l.size()-1; i >= 0; i--) {
					View v = l.get(i);
					if (v.isAttachedToWindow() && v.isShown() &&
					    (v.getWidth() > 0) &&
					    v.hasWindowFocus()) {
						ret[0] = v;
						return;
					}
				}
				for (int i = l.size()-1; i >= 0; i--) {
					View v = l.get(i);
					if (v.isAttachedToWindow() && v.isShown() &&
					    (v.getWidth() > 0)) {
						ret[0] = v;
						return;
					}
				}
			} });
		return ret[0];
	}

	/* checks the topmost window and takes a screenshot */
	void check(final String screen) {
		settle(400);
		final View root = top_root();
		if (root == null) {
			add(screen, "NO_WINDOW", "-", "no window shown");
			return;
		}
		inst.runOnMainSync(new Runnable() {
			public void run() {
				check_window(screen, root);
			} });
		atf(screen, root);
		screenshot(screen);
	}

	private static class item {
		View v;
		Rect r;		/* in window coordinates */
		boolean target;	/* touch target */
	}

	private void check_window(String screen, View root) {
		float dp = root.getResources().getDisplayMetrics().density;
		int w = root.getWidth(), h = root.getHeight();

		/* the window itself on the display */
		int[] loc = new int[2];
		root.getLocationOnScreen(loc);
		WindowManager wm = (WindowManager)target.getSystemService(
					Context.WINDOW_SERVICE);
		Rect disp = wm.getCurrentWindowMetrics().getBounds();
		Rect win = new Rect(loc[0], loc[1], loc[0]+w, loc[1]+h);
		if (!disp.contains(win)) {
			add(screen, "OFFSCREEN", "window", "window " +
				win.toShortString() + " not inside display " +
				disp.toShortString());
		}

		/* what covers the window's edges */
		WindowInsets wi = root.getRootWindowInsets();
		Insets bars = Insets.NONE;
		List<Rect> cutouts = new ArrayList<Rect>();
		if (wi != null) {
			bars = wi.getInsets(WindowInsets.Type.systemBars());
			DisplayCutout dc = wi.getDisplayCutout();
			if (dc != null) {
				cutouts.addAll(dc.getBoundingRects());
			}
		}
		List<Rect> bar_rects = new ArrayList<Rect>();
		if (bars.top > 0) bar_rects.add(new Rect(0, 0, w, bars.top));
		if (bars.bottom > 0) bar_rects.add(new Rect(0, h-bars.bottom, w, h));
		if (bars.left > 0) bar_rects.add(new Rect(0, 0, bars.left, h));
		if (bars.right > 0) bar_rects.add(new Rect(w-bars.right, 0, w, h));

		List<item> items = new ArrayList<item>();
		collect(root, items);

		for (item it : items) {
			View v = it.v;
			String name = name(v);
			Rect r = it.r;

			/* inside the window and not cut by a parent */
			if (!new Rect(0, 0, w, h).contains(r)) {
				add(screen, "OFFSCREEN", name, "at " +
					r.toShortString() + ", window " + w +
					"x" + h);
			}
			Rect vis = new Rect();
			boolean any = v.getGlobalVisibleRect(vis);
			long full = (long)r.width() * r.height();
			long seen = any ? ((long)vis.width() * vis.height()) : 0;
			if ((seen < full) && !in_scroller(v) &&
			    ((full - seen) > 2L*Math.max(r.width(), r.height()))) {
				add(screen, "CLIPPED", name, (any ? ("only " +
					(100*seen/full) + "% visible, " +
					vis.toShortString() + " of ")
					: "not visible at all, ") +
					r.toShortString());
			}

			/* system bars and cutout */
			Rect shown = any ? vis : r;
			for (Rect b : bar_rects) {
				Rect x = new Rect();
				if (x.setIntersect(shown, b)) {
					add(screen, "UNDER_BARS", name,
						px(x, dp) + " of " + px(r, dp) +
						" under bar " + px(b, dp));
				}
			}
			for (Rect c : cutouts) {
				Rect x = new Rect();
				if (x.setIntersect(shown, c)) {
					add(screen, "UNDER_CUTOUT", name,
						px(x, dp) + " under cutout " +
						px(c, dp));
				}
			}

			if (it.target) {
				float tw = r.width()/dp, th = r.height()/dp;
				if ((tw < MIN_TOUCH_DP - 0.5f) ||
				    (th < MIN_TOUCH_DP - 0.5f)) {
					add(screen, "TOUCH_TARGET", name,
						String.format(Locale.ROOT,
						"%.0fx%.0fdp < %dx%ddp", tw, th,
						MIN_TOUCH_DP, MIN_TOUCH_DP));
				}
				if (!has_label(v)) {
					add(screen, "NO_LABEL", name,
						"no text or contentDescription");
				}
			}
			if ((v instanceof TextView) && !(v instanceof EditText)) {
				check_text(screen, name, (TextView)v);
			}
		}

		/* overlaps between shown things, ignoring parent/child */
		for (int i = 0; i < items.size(); i++) {
			for (int j = i+1; j < items.size(); j++) {
				item a = items.get(i), b = items.get(j);
				if (related(a.v, b.v)) {
					continue;
				}
				Rect x = new Rect();
				if (x.setIntersect(a.r, b.r) &&
				    (x.width() > 1) && (x.height() > 1)) {
					add(screen, "OVERLAP", name(a.v) + "~" +
						name(b.v), px(x, dp) +
						" shared by " + px(a.r, dp) +
						" and " + px(b.r, dp));
				}
			}
		}
	}

	private static String px(Rect r, float dp) {
		return String.format(Locale.ROOT, "[%.0f,%.0f %.0fx%.0fdp]",
			r.left/dp, r.top/dp, r.width()/dp, r.height()/dp);
	}

	private void check_text(String screen, String name, TextView tv) {
		CharSequence t = tv.getText();
		Layout l = tv.getLayout();
		if ((t == null) || (t.length() == 0) || (l == null)) {
			return;
		}
		int lines = l.getLineCount();
		for (int i = 0; i < lines; i++) {
			if (l.getEllipsisCount(i) > 0) {
				add(screen, "TEXT_ELLIPSIZED", name, "\"" + t +
					"\" shown as \"" + shown_text(l, i) +
					"\"");
				break;
			}
		}
		float dp = tv.getResources().getDisplayMetrics().density;
		int aw = tv.getWidth() - tv.getCompoundPaddingLeft() -
				tv.getCompoundPaddingRight();
		int ah = tv.getHeight() - tv.getExtendedPaddingTop() -
				tv.getExtendedPaddingBottom();
		float lw = 0.0f;
		for (int i = 0; i < lines; i++) {
			lw = Math.max(lw, l.getLineWidth(i));
		}
		if (lw > aw + 1.0f) {
			add(screen, "TEXT_CUT_H", name, String.format(Locale.ROOT,
				"\"%s\" needs %.0fdp, has %.0fdp", t, lw/dp,
				aw/dp));
		}
		/* the last line's baseline and half its descent must show */
		float need = l.getLineBaseline(lines-1) +
				tv.getPaint().getFontMetrics().descent/2.0f;
		if ((ah > 0) && (need > ah + 1.0f)) {
			add(screen, "TEXT_CUT_V", name, String.format(Locale.ROOT,
				"\"%s\" needs %.0fdp high, has %.0fdp", t,
				need/dp, ah/dp));
		}
	}

	private static String shown_text(Layout l, int line) {
		CharSequence t = l.getText();
		int s = l.getLineStart(line);
		int e = l.getLineEnd(line);
		int es = l.getEllipsisStart(line);
		int ec = l.getEllipsisCount(line);
		if (ec <= 0) {
			return t.subSequence(s, e).toString();
		}
		return t.subSequence(s, s+es).toString() + "..." +
			t.subSequence(Math.min(s+es+ec, e), e).toString();
	}

	/* the views that matter, in drawing order */
	private void collect(View v, List<item> out) {
		if ((v.getVisibility() != View.VISIBLE) || (v.getAlpha() == 0.0f)
		    || (v.getWidth() <= 0) || (v.getHeight() <= 0)) {
			return;
		}
		boolean target = is_target(v);
		boolean text = (v instanceof TextView) &&
			(((TextView)v).getText() != null) &&
			(((TextView)v).getText().length() > 0);
		if (target || text || (v instanceof SurfaceView)) {
			item it = new item();
			it.v = v;
			it.target = target;
			int[] p = new int[2];
			v.getLocationInWindow(p);
			it.r = new Rect(p[0], p[1], p[0]+v.getWidth(),
					p[1]+v.getHeight());
			out.add(it);
		}
		if (v instanceof ViewGroup) {
			ViewGroup g = (ViewGroup)v;
			for (int i = 0; i < g.getChildCount(); i++) {
				collect(g.getChildAt(i), out);
			}
		}
	}

	/* clickable things; list rows count, the list itself doesn't */
	private static boolean is_target(View v) {
		if (v instanceof AdapterView) {
			return false;
		}
		if (v.getParent() instanceof AdapterView) {
			return true;
		}
		return v.isClickable() || v.isLongClickable() ||
			(v instanceof EditText);
	}

	private static boolean has_label(View v) {
		if ((v.getContentDescription() != null) &&
		    (v.getContentDescription().length() > 0)) {
			return true;
		}
		if (v instanceof TextView) {
			TextView t = (TextView)v;
			if (((t.getText() != null) && (t.getText().length() > 0))
			    || ((t.getHint() != null) &&
				(t.getHint().length() > 0))) {
				return true;
			}
		}
		if (v instanceof ViewGroup) {
			ViewGroup g = (ViewGroup)v;
			for (int i = 0; i < g.getChildCount(); i++) {
				View c = g.getChildAt(i);
				if ((c.getVisibility() == View.VISIBLE) &&
				    !c.isClickable() && has_label(c)) {
					return true;
				}
			}
		}
		return false;
	}

	/* clipping by a list or scroll view that can scroll is fine */
	private static boolean in_scroller(View v) {
		for (ViewParent p = v.getParent(); p instanceof View;
		     p = p.getParent()) {
			View pv = (View)p;
			if ((pv instanceof AbsListView) ||
			    (pv instanceof ScrollView) ||
			    (pv instanceof HorizontalScrollView) ||
			    pv.getClass().getName().contains("RecyclerView")) {
				if (pv.canScrollVertically(1) ||
				    pv.canScrollVertically(-1) ||
				    pv.canScrollHorizontally(1) ||
				    pv.canScrollHorizontally(-1)) {
					return true;
				}
			}
		}
		return false;
	}

	private static boolean related(View a, View b) {
		return is_ancestor(a, b) || is_ancestor(b, a);
	}

	private static boolean is_ancestor(View anc, View v) {
		for (ViewParent p = v.getParent(); p != null;
		     p = p.getParent()) {
			if (p == anc) {
				return true;
			}
		}
		return false;
	}

	/* id name if it has one (plus the text for list rows and framework
	 * ids), else class and text */
	static String name(View v) {
		String id = null;
		boolean fw = false;
		if ((v.getId() != View.NO_ID) && (v.getId() > 0)) {
			try {
				id = v.getResources().getResourceEntryName(
						v.getId());
				fw = v.getResources().getResourcePackageName(
						v.getId()).equals("android");
			} catch (Exception e) {
			}
		}
		CharSequence t = null;
		if (v instanceof TextView) {
			t = ((TextView)v).getText();
		}
		if ((t == null) || (t.length() == 0)) {
			t = v.getContentDescription();
		}
		String ts = null;
		if ((t != null) && (t.length() > 0)) {
			ts = t.toString().replaceAll("\\s+", " ").trim();
			if (ts.length() > 24) {
				ts = ts.substring(0, 24);
			}
			ts = ts.replace('|', '/');
		}
		if ((id != null) && !fw && !id.startsWith("list_entry")) {
			return id;
		}
		String cls = v.getClass().getSimpleName();
		if (cls.isEmpty()) {
			cls = v.getClass().getSuperclass().getSimpleName();
		}
		return cls + ((id != null) ? ("#" + id) : "") +
			((ts != null) ? ("\"" + ts + "\"") : "");
	}

	/* ---- Accessibility Test Framework ---- */

	private static final Set<String> ATF_SKIP = new LinkedHashSet<String>();
	static {
		/* covered by our own TOUCH_TARGET and NO_LABEL */
		ATF_SKIP.add("TouchTargetSizeCheck");
		ATF_SKIP.add("SpeakableTextPresentCheck");
	}

	private void atf(final String screen, final View root) {
		final List<AccessibilityHierarchyCheckResult> res =
			new ArrayList<AccessibilityHierarchyCheckResult>();
		final AccessibilityHierarchyAndroid[] hier =
			new AccessibilityHierarchyAndroid[1];
		try {
			inst.runOnMainSync(new Runnable() {
				public void run() {
					hier[0] = AccessibilityHierarchyAndroid
						.newBuilder(root).build();
				} });
			for (AccessibilityHierarchyCheck c :
				AccessibilityCheckPreset
				.getAccessibilityHierarchyChecksForPreset(
					AccessibilityCheckPreset.LATEST)) {
				if (ATF_SKIP.contains(
					c.getClass().getSimpleName())) {
					continue;
				}
				res.addAll(c.runCheckOnHierarchy(hier[0]));
			}
		} catch (Throwable t) {
			Log.w(TAG, "ATF failed", t);
			return;
		}
		for (AccessibilityHierarchyCheckResult r : res) {
			AccessibilityCheckResultType ty = r.getType();
			if ((ty != AccessibilityCheckResultType.ERROR) &&
			    (ty != AccessibilityCheckResultType.WARNING)) {
				continue;
			}
			ViewHierarchyElement e = r.getElement();
			String elem = "-";
			if (e != null) {
				String rn = e.getResourceName();
				CharSequence cn = e.getClassName();
				String cls = (cn == null) ? "?" : cn.toString()
					.replaceAll(".*\\.", "");
				if (rn != null) {
					rn = rn.replaceAll(".*:id/", "");
				}
				CharSequence tx = e.getText();
				String ts = (tx == null) ? "" : tx.toString()
					.replaceAll("\\s+", " ").trim();
				if (ts.length() > 24) {
					ts = ts.substring(0, 24);
				}
				elem = ((rn != null) && !rn.startsWith("list_entry"))
					? rn : (cls + (ts.isEmpty() ? "" :
						("\"" + ts.replace('|', '/') +
						 "\"")));
			}
			String check = r.getSourceCheckClass().getSimpleName()
					.replace("Check", "");
			String msg = String.valueOf(r.getMessage(Locale.ENGLISH))
					.replaceAll("\\s+", " ");
			add(screen, "ATF_" + check, elem, ty + ": " + msg,
				ty != AccessibilityCheckResultType.ERROR);
		}
	}

	/* ---- helpers for the tests ---- */

	/* screen coordinates of a view's centre */
	int[] center(final View v) {
		final int[] p = new int[2];
		inst.runOnMainSync(new Runnable() {
			public void run() {
				v.getLocationOnScreen(p);
				p[0] += v.getWidth()/2;
				p[1] += v.getHeight()/2;
			} });
		return p;
	}

	void long_press(View v) {
		int[] c = center(v);
		dev.swipe(c[0], c[1], c[0], c[1], 150);	/* ~0.75s */
		settle(500);
	}

	void back() {
		dev.pressBack();
		settle(400);
	}

	static List<String> sorted(Collection<String> c) {
		List<String> l = new ArrayList<String>(c);
		Collections.sort(l);
		return l;
	}
}
