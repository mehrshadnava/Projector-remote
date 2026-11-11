package com.projector.blank;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Point;
import android.os.Bundle;
import android.os.IBinder;
import android.os.Parcel;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.Toast;

/**
 * Manual 4-corner keystone, driven by the phone remote:
 * arrows move the selected corner, OK picks the next corner, Menu resets, Back saves and exits.
 *
 * Talks to the firmware's "keystone" system service (android.app.IKeystoneService), found by reading
 * framework.jar on this projector. Offsets are panel pixels; positive = corner moves inward. The service
 * stores them in persist.display.* so they survive reboots; update() makes SurfaceFlinger redraw.
 */
public class KeystoneActivity extends Activity {
    // IKeystoneService transaction codes (this firmware only)
    private static final int SET_AUTO_VERTICAL = 1, RESET = 2, UPDATE = 10;
    private static final int[] SET_CORNER = {4, 5, 7, 6}; // top-left, top-right, bottom-right, bottom-left
    private static final String[] PROP = {"lt", "rt", "rb", "lb"};
    private static final String[] NAME = {"Top-left", "Top-right", "Bottom-right", "Bottom-left"};
    // screen direction -> offset sign, so "right" always moves the corner right on the wall
    private static final int[] SX = {1, -1, -1, 1}, SY = {1, 1, -1, -1};
    private static final float STEP = 3f;

    private IBinder keystone;
    private final float[][] off = new float[4][2];
    private int corner;
    private float minX, minY, maxX, maxY;
    private View grid;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        try {
            keystone = (IBinder) Class.forName("android.os.ServiceManager")
                    .getMethod("getService", String.class).invoke(null, "keystone");
        } catch (Exception e) {
            keystone = null;
        }
        if (keystone == null) {
            Toast.makeText(this, "This projector has no keystone service", Toast.LENGTH_LONG).show();
            finish();
            return;
        }
        call(SET_AUTO_VERTICAL, 0); // manual mode: the tilt sensor would overwrite our corners

        Point size = new Point();
        getWindowManager().getDefaultDisplay().getRealSize(size);
        float zoom = parse(prop("persist.display.size"), 80f) / 100f;
        // outward limit = panel edge; inward limit keeps 20px of picture around the center
        minX = size.x / 2f - size.x / 2f / zoom;
        minY = size.y / 2f - size.y / 2f / zoom;
        maxX = size.x / 2f - 20;
        maxY = size.y / 2f - 20;
        for (int i = 0; i < 4; i++) {
            String[] xy = prop("persist.display." + PROP[i]).split(",");
            off[i][0] = xy.length == 2 ? parse(xy[0], 0) : 0;
            off[i][1] = xy.length == 2 ? parse(xy[1], 0) : 0;
        }

        grid = new Grid();
        grid.setSystemUiVisibility(View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_FULLSCREEN
                | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
        setContentView(grid);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    }

    /** Pressing the keystone key again closes it. */
    @Override
    protected void onNewIntent(Intent i) { finish(); }

    @Override
    public boolean onKeyDown(int code, KeyEvent e) {
        switch (code) {
            case KeyEvent.KEYCODE_DPAD_LEFT: move(-1, 0); return true;
            case KeyEvent.KEYCODE_DPAD_RIGHT: move(1, 0); return true;
            case KeyEvent.KEYCODE_DPAD_UP: move(0, -1); return true;
            case KeyEvent.KEYCODE_DPAD_DOWN: move(0, 1); return true;
            case KeyEvent.KEYCODE_DPAD_CENTER: case KeyEvent.KEYCODE_ENTER: case KeyEvent.KEYCODE_TAB:
                corner = (corner + 1) % 4;
                grid.invalidate();
                return true;
            case KeyEvent.KEYCODE_MENU:
                call(RESET);
                for (float[] o : off) { o[0] = 0; o[1] = 0; }
                grid.invalidate();
                return true;
            case KeyEvent.KEYCODE_BACK: case KeyEvent.KEYCODE_ESCAPE:
                finish(); // the service already saved every move
                return true;
        }
        return super.onKeyDown(code, e);
    }

    private void move(int sx, int sy) {
        float[] o = off[corner];
        o[0] = clamp(o[0] + sx * SX[corner] * STEP, minX, maxX);
        o[1] = clamp(o[1] + sy * SY[corner] * STEP, minY, maxY);
        call(SET_CORNER[corner], o[0], o[1]);
        call(UPDATE);
        grid.invalidate();
    }

    /** Binder call to IKeystoneService; one int argument means a boolean. */
    private void call(int code, float... args) {
        Parcel data = Parcel.obtain(), reply = Parcel.obtain();
        try {
            data.writeInterfaceToken("android.app.IKeystoneService");
            for (float a : args) data.writeFloat(a);
            keystone.transact(code, data, reply, 0);
            reply.readException();
        } catch (Exception e) {
            Toast.makeText(this, "Keystone call failed: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        } finally {
            data.recycle();
            reply.recycle();
        }
    }

    private void call(int code, int boolArg) {
        Parcel data = Parcel.obtain(), reply = Parcel.obtain();
        try {
            data.writeInterfaceToken("android.app.IKeystoneService");
            data.writeInt(boolArg);
            keystone.transact(code, data, reply, 0);
            reply.readException();
        } catch (Exception ignored) {
        } finally {
            data.recycle();
            reply.recycle();
        }
    }

    private static String prop(String key) {
        try {
            return (String) Class.forName("android.os.SystemProperties").getMethod("get", String.class).invoke(null, key);
        } catch (Exception e) {
            return "";
        }
    }

    private static float parse(String s, float fallback) {
        try { return Float.parseFloat(s.trim()); } catch (Exception e) { return fallback; }
    }

    private static float clamp(float v, float lo, float hi) { return Math.max(lo, Math.min(hi, v)); }

    /** Alignment grid. It is warped together with everything else, so its edges show the real result on the wall. */
    private class Grid extends View {
        private final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG), edge = new Paint(Paint.ANTI_ALIAS_FLAG),
                dot = new Paint(Paint.ANTI_ALIAS_FLAG), text = new Paint(Paint.ANTI_ALIAS_FLAG);

        Grid() {
            super(KeystoneActivity.this);
            setBackgroundColor(0xFF12163A);
            line.setColor(0x40FFFFFF);
            line.setStrokeWidth(1);
            edge.setColor(0xFFFFFFFF);
            edge.setStyle(Paint.Style.STROKE);
            edge.setStrokeWidth(4);
            text.setColor(0xFFEEF1FF);
            text.setTextAlign(Paint.Align.CENTER);
        }

        @Override
        protected void onDraw(Canvas c) {
            float w = getWidth(), h = getHeight();
            for (float x = w / 16; x < w; x += w / 16) c.drawLine(x, 0, x, h, line);
            for (float y = h / 10; y < h; y += h / 10) c.drawLine(0, y, w, y, line);
            c.drawRect(2, 2, w - 2, h - 2, edge);
            c.drawLine(w / 2 - 20, h / 2, w / 2 + 20, h / 2, edge);
            c.drawLine(w / 2, h / 2 - 20, w / 2, h / 2 + 20, edge);

            float[][] pos = {{0, 0}, {w, 0}, {w, h}, {0, h}};
            for (int i = 0; i < 4; i++) {
                dot.setColor(i == corner ? 0xFF4DE1FF : 0x66FFFFFF);
                c.drawCircle(pos[i][0], pos[i][1], i == corner ? 46 : 26, dot);
            }

            text.setTextSize(30);
            text.setFakeBoldText(true);
            c.drawText("Keystone · " + NAME[corner] + " corner", w / 2, h / 2 - 50, text);
            text.setTextSize(19);
            text.setFakeBoldText(false);
            c.drawText("Arrows: move corner   ·   OK: next corner", w / 2, h / 2 + 50, text);
            c.drawText("Menu: reset   ·   Back: done (saved automatically)", w / 2, h / 2 + 78, text);
        }
    }
}
