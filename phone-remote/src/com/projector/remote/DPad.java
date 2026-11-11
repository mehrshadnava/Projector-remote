package com.projector.remote;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;

/** Round D-pad: four wedges (arrow keys) around an OK button. The pressed part lights up. */
public class DPad extends View {
    // zones go clockwise from +x on screen: right, down, left, up
    private static final int[] CODES = {0x4F, 0x51, 0x50, 0x52};
    private static final int OK_ZONE = 4, OK_CODE = 0x28;
    private static final int ACCENT = 0xFF4DE1FF;

    MainActivity act;
    private int zone = -1;
    private final float d = getResources().getDisplayMetrics().density;
    private final Paint base = paint(0xFF262C5C), rim = paint(0x664DE1FF), glow = paint(0x554DE1FF),
            center = paint(0xFF1C2148), arrow = paint(0xFFC8D0FF), label = paint(0xFFEEF1FF);
    private final RectF oval = new RectF();
    private final Path tri = new Path();

    public DPad(Context c, AttributeSet a) {
        super(c, a);
        rim.setStyle(Paint.Style.STROKE);
        rim.setStrokeWidth(2 * d);
        label.setTextAlign(Paint.Align.CENTER);
        label.setTextSize(22 * d);
        label.setFakeBoldText(true);
    }

    private static Paint paint(int color) {
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setColor(color);
        return p;
    }

    @Override
    protected void onDraw(Canvas c) {
        float cx = getWidth() / 2f, cy = getHeight() / 2f;
        float R = Math.min(cx, cy) - 3 * d, r = R * 0.38f;
        c.drawCircle(cx, cy, R, base);
        if (zone >= 0 && zone < OK_ZONE) {
            oval.set(cx - R, cy - R, cx + R, cy + R);
            c.drawArc(oval, zone * 90 - 45, 90, true, glow);
        }
        c.drawCircle(cx, cy, R, rim);

        float s = 13 * d, mid = (R + r) / 2;
        for (int i = 0; i < 4; i++) {
            float cos = (float) Math.cos(Math.toRadians(i * 90)), sin = (float) Math.sin(Math.toRadians(i * 90));
            float px = cx + cos * mid, py = cy + sin * mid;
            tri.reset();
            tri.moveTo(px + cos * s, py + sin * s);
            tri.lineTo(px - cos * s * 0.6f - sin * s, py - sin * s * 0.6f + cos * s);
            tri.lineTo(px - cos * s * 0.6f + sin * s, py - sin * s * 0.6f - cos * s);
            tri.close();
            arrow.setColor(zone == i ? ACCENT : 0xFFC8D0FF);
            c.drawPath(tri, arrow);
        }

        center.setColor(zone == OK_ZONE ? ACCENT : 0xFF1C2148);
        c.drawCircle(cx, cy, r, center);
        c.drawCircle(cx, cy, r, rim);
        label.setColor(zone == OK_ZONE ? 0xFF12163A : 0xFFEEF1FF);
        c.drawText("OK", cx, cy - (label.descent() + label.ascent()) / 2, label);
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN: {
                float cx = getWidth() / 2f, cy = getHeight() / 2f;
                float R = Math.min(cx, cy), dx = e.getX() - cx, dy = e.getY() - cy;
                double dist = Math.hypot(dx, dy);
                if (dist > R) return false; // corners outside the circle
                zone = dist < R * 0.38f ? OK_ZONE : (int) (((Math.toDegrees(Math.atan2(dy, dx)) + 405) % 360) / 90);
                act.press(this, 'k', code());
                invalidate();
                return true;
            }
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                if (zone >= 0) act.release('k', code());
                zone = -1;
                invalidate();
                return true;
        }
        return true;
    }

    private int code() { return zone == OK_ZONE ? OK_CODE : CODES[zone]; }
}
