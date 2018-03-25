package org.galexander.tunertime;

import android.content.SharedPreferences;
import java.util.ArrayList;

public class preset {
	public int id;
	public String name;
	public ArrayList<Integer> notes;

	public static ArrayList<preset> presets = new ArrayList<preset>();


	public static preset make() {
		preset ret = new preset();
		int highest_id = 0;
		for (preset p : presets) {
			if (p.id > highest_id) {
				highest_id = p.id;
			}
		}
		ret.id = highest_id+1;
		ret.name = "unnamed";
		ret.notes = new ArrayList<Integer>();
		presets.add(ret);
		return ret;
	}

	public static preset find(int id) {
		for (preset p : presets) {
			if (p.id == id) {
				return p;
			}
		}
		return null;
	}

	public static void load(SharedPreferences p) {
		String ids_str = p.getString("presets", "");
		ArrayList<Integer> ids = csi_to_intarr(ids_str);
		presets.clear();
		for (int id : ids) {
			String name = p.getString("preset_"+id+"_name", null);
			String notes = p.getString("preset_"+id+"_notes", null);
			if ((name != null) && (notes != null)) {
				preset n = new preset();
				n.id = id;
				n.name = name;
				n.notes = csi_to_intarr(notes);
				presets.add(n);
			}
		}
	}

	public static void save(SharedPreferences.Editor e) {
		e.putString("presets", presetarr_to_csi(presets));
		for (preset p : presets) {
			e.putString("preset_"+p.id+"_name", p.name);
			e.putString("preset_"+p.id+"_notes",
						intarr_to_csi(p.notes));
		}
	}

	private static ArrayList<Integer> csi_to_intarr(String s) {
		byte[] b = s.getBytes();
		int val = 0;
		boolean any_val = false;
		ArrayList<Integer> ret = new ArrayList<Integer>();
		for (int i = 0; i < b.length; i++) {
			byte c = b[i];
			if ((c == ',') && any_val) {
				ret.add(val);
				val = 0;
				any_val = false;
			} else if ((c >= '0') && (c <= '9')) {
				val = (val*10) + (c-'0');
				any_val = true;
			}
		}
		if (any_val) {
			ret.add(val);
		}
		return ret;
	}
	private static String intarr_to_csi(ArrayList<Integer> arr) {
		String ret = "";
		String comma = "";
		for (int i : arr) {
			ret += comma + i;
			comma = ",";
		}
		return ret;
	}
	private static String presetarr_to_csi(ArrayList<preset> arr) {
		String ret = "";
		String comma = "";
		for (preset p : arr) {
			ret += comma + p.id;
			comma = ",";
		}
		return ret;
	}
}
