package org.galexander.tunertime;

import android.view.inputmethod.EditorInfo;
import android.view.KeyEvent;
import android.widget.TextView;

public abstract class SubmitListener
			implements TextView.OnEditorActionListener {
	public boolean onEditorAction(TextView v, int actionId, KeyEvent e) {
		if ((actionId == EditorInfo.IME_ACTION_DONE) ||
		    ((e != null) &&
		     (e.getKeyCode() == KeyEvent.KEYCODE_ENTER) &&
		     (e.getAction() == KeyEvent.ACTION_UP))) {
			onSubmit(v.getText().toString());
		}
		return (actionId != EditorInfo.IME_ACTION_DONE);
	}
	abstract public void onSubmit(String s);
}
