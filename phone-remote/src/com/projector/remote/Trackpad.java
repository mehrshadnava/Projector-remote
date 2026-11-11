package com.projector.remote;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.os.SystemClock;
import android.util.AttributeSet;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;

/** Turns finger movement into relative HID mouse reports, with a glow under the finger and a ripple on tap. */
public class Trackpad extends View {
    // shortcut: fixed speed, no acceleration curve; tune after trying it on the projector.
    private static final float SPEED = 1.5f;       // projector pixels per phone dp
    private static final float SCROLL_STEP = 40f;  // finger px per wheel notch
    private static final float TAP_SLOP = 25f;     // px of travel before a touch stops being a tap
    private static final long RIPPLE_MS = 400;

    MainActivity act;
    private float lastX, lastY, accX, accY, accScroll, travel;
    private long downAt, rippleAt;
    private int maxPointers;
    private boolean resync, touching, used;
    private float tx, ty, rx, ry; // finger and ripple positions, for drawing

    private final float d = getResources().getDisplayMetrics().density;
    private final Paint dot = new Paint(Paint.ANTI_ALIAS_FLAG), glow = new Paint(Paint.ANTI_ALIAS_FLAG),
            ring = new Paint(Paint.ANTI_ALIAS_FLAG), hint = new Paint(Paint.ANTI_ALIAS_FLAG);

    public Trackpad(Context c, AttributeSet a) {
        super(c, a);
        setClipToOutline(true); // keep the glow inside the rounded background
        dot.setColor(0x26FFFFFF);
        ring.setStyle(Paint.Style.STROKE);
        ring.setStrokeWidth(3 * d);
        hint.setColor(0x99EEF1FF);
        hint.setTextAlign(Paint.Align.CENTER);
        hint.setTextSize(15 * d);
    }

    @Override
    protected void onDraw(Canvas c) {
        float step = 28 * d, w = getWidth(), h = getHeight();
        for (float x = step / 2; x < w; x += step)
            for (float y = step / 2; y < h; y += step) c.drawCircle(x, y, 1.5f * d, dot);

        if (touching) {
            glow.setShader(new RadialGradient(tx, ty, 80 * d, 0x804DE1FF, 0x004DE1FF, Shader.TileMode.CLAMP));
            c.drawCircle(tx, ty, 80 * d, glow);
        } else if (!used) {
            c.drawText("Drag to move the cursor", w / 2, h / 2 - 14 * d, hint);
            c.drawText("Tap = click · Two fingers = scroll / back", w / 2, h / 2 + 14 * d, hint);
        }

        long age = SystemClock.uptimeMillis() - rippleAt;
        if (age < RIPPLE_MS) {
            float f = age / (float) RIPPLE_MS;
            ring.setColor(Color.argb((int) (255 * (1 - f)), 0x4D, 0xE1, 0xFF));
            c.drawCircle(rx, ry, (16 + 60 * f) * d, ring);
            postInvalidateOnAnimation();
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downAt = e.getEventTime();
                maxPointers = 1;
                accX = accY = accScroll = travel = 0;
                lastX = tx = e.getX();
                lastY = ty = e.getY();
                touching = used = true;
                invalidate();
                return true;
            case MotionEvent.ACTION_POINTER_DOWN:
            case MotionEvent.ACTION_POINTER_UP:
                maxPointers = Math.max(maxPointers, e.getPointerCount());
                resync = true; // pointer 0 may change, avoid a jump
                return true;
            case MotionEvent.ACTION_MOVE: {
                float x = e.getX(), y = e.getY();
                tx = x; ty = y;
                invalidate();
                if (resync) { lastX = x; lastY = y; resync = false; return true; }
                float dx = x - lastX, dy = y - lastY;
                lastX = x; lastY = y;
                travel += Math.abs(dx) + Math.abs(dy);
                if (e.getPointerCount() >= 2) {
                    accScroll += dy;
                    int w = (int) (accScroll / SCROLL_STEP); // finger up -> negative wheel -> page scrolls down
                    if (w != 0) { accScroll -= w * SCROLL_STEP; act.mouse(0, 0, 0, clamp(w)); }
                } else {
                    float k = SPEED / d;
                    accX += dx * k; accY += dy * k;
                    int mx = (int) accX, my = (int) accY;
                    accX -= mx; accY -= my;
                    while (mx != 0 || my != 0) {
                        int sx = clamp(mx), sy = clamp(my);
                        act.mouse(0, sx, sy, 0);
                        mx -= sx; my -= sy;
                    }
                }
                return true;
            }
            case MotionEvent.ACTION_UP:
                touching = false;
                if (travel < TAP_SLOP && e.getEventTime() - downAt < 300) {
                    act.click(maxPointers >= 2 ? 2 : 1); // right click = Back on Android
                    performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
                    rx = tx; ry = ty;
                    rippleAt = SystemClock.uptimeMillis();
                }
                invalidate();
                return true;
            case MotionEvent.ACTION_CANCEL:
                touching = false;
                invalidate();
                return true;
        }
        return super.onTouchEvent(e);
    }

    private static int clamp(int v) { return Math.max(-127, Math.min(127, v)); }
}
