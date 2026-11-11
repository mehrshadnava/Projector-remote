package com.projector.blank;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;

/** Black screen with the lamp (backlight) at its lowest level. Any navigation key or click turns it back on. */
public class BlankActivity extends Activity {
    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        View v = new View(this);
        v.setBackgroundColor(Color.BLACK);
        v.setSystemUiVisibility(View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_FULLSCREEN
                | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
        setContentView(v);
        WindowManager.LayoutParams lp = getWindow().getAttributes();
        lp.screenBrightness = 0f; // Android clamps this to the lowest backlight level (1/255 on this projector)
        getWindow().setAttributes(lp);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON); // never let Android sleep (firmware would power off)
    }

    /** Pressing the screen-off key again toggles back on. */
    @Override
    protected void onNewIntent(Intent i) { finish(); }

    /** Leaving (Home, another app) restores the lamp; never linger in the background. */
    @Override
    protected void onStop() { super.onStop(); finish(); }

    @Override
    public boolean onKeyDown(int code, KeyEvent e) {
        if (passesThrough(code)) return super.onKeyDown(code, e);
        finish();
        return true;
    }

    /** Volume and media keys work without lighting the lamp (audio-only mode). */
    static boolean passesThrough(int code) {
        switch (code) {
            case KeyEvent.KEYCODE_VOLUME_UP: case KeyEvent.KEYCODE_VOLUME_DOWN: case KeyEvent.KEYCODE_VOLUME_MUTE:
            case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE: case KeyEvent.KEYCODE_MEDIA_PLAY: case KeyEvent.KEYCODE_MEDIA_PAUSE:
            case KeyEvent.KEYCODE_MEDIA_NEXT: case KeyEvent.KEYCODE_MEDIA_PREVIOUS: case KeyEvent.KEYCODE_MEDIA_STOP:
            case KeyEvent.KEYCODE_CALCULATOR: // the toggle key itself arrives via onNewIntent
                return true;
        }
        return false;
    }

    @Override
    public boolean dispatchTouchEvent(MotionEvent e) {
        if (e.getActionMasked() == MotionEvent.ACTION_DOWN) finish(); // mouse click wakes; mouse movement alone doesn't
        return true;
    }
}
