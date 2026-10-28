package com.jadflat.chgame;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.util.SparseIntArray;
import android.view.MotionEvent;
import android.view.View;

/**
 * On-screen controls for a touch screen: a d-pad bottom-left, A and B
 * bottom-right, START and SELECT in the bottom centre. Drawn translucent over
 * the game. A touch outside them returns false, so it falls through to the
 * game. Each finger holds what it touched until it lifts.
 */
final class TouchControls extends View {
    private static final int NONE = 0, DPAD = 1, A = 2, B = 3, START = 4, SELECT = 5;
    /* a direction counts once the touch is past 22.5 degrees off it (tan 22.5 = 0.414): diagonals between */
    private static final float K = 0.414f;

    private static final int LINE = 0x59FFFFFF, FILL = 0x1FFFFFFF, PRESSED_FILL = 0x59FFFFFF;
    private static final int PRESSED_LINE = 0xB3FFFFFF, LABEL = 0x8CFFFFFF;

    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private final Path cross = new Path();

    /* layout in pixels, made in onSizeChanged; unit scales the sizes (1 except a small portrait space) */
    private float unit = 1f;
    private float dpadX, dpadY, dpadR, arm, dead, slop;
    private float aX, aY, bX, bY, btnR;
    private float pillW, pillH, pillY, selX, startX;
    private float textBig, textSmall;

    /* which control each finger is on, and the buttons it holds */
    private final SparseIntArray owner = new SparseIntArray();
    private final SparseIntArray bits = new SparseIntArray();
    private volatile int held;

    TouchControls(Context c) {
        super(c);
        line.setStyle(Paint.Style.STROKE);
        fill.setStyle(Paint.Style.FILL);
        text.setTypeface(Ui.MEDIUM);
        text.setTextAlign(Paint.Align.CENTER);
    }

    /** A size in dp, scaled by unit. */
    private float dp(float v) {
        return Ui.dp(getContext(), v) * unit;
    }

    /** The Native button bits held now (the OR of every finger). */
    int buttons() {
        return held;
    }

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        float W = w, H = h;
        /* portrait: the pad sits in the space under the picture, so its parts shrink to fit it */
        unit = getResources().getConfiguration().orientation == android.content.res.Configuration.ORIENTATION_PORTRAIT
                ? Math.min(1f, Math.min(W / Ui.dp(getContext(), 340), H / Ui.dp(getContext(), 200))) : 1f;
        dpadR = dp(66);
        arm = dp(21);
        dead = dp(12);
        slop = dp(10);
        dpadX = dp(20) + dpadR;
        dpadY = H - dp(20) - dpadR;

        btnR = dp(30);
        aX = W - dp(22) - btnR;                         /* A: to the right and higher */
        aY = H - dp(22) - btnR - dp(40);
        bX = aX - dp(68);                               /* B: lower-left of A */
        bY = aY + dp(40);

        pillW = dp(64);
        pillH = dp(22);
        pillY = H - dp(26);
        selX = W / 2 - dp(50);
        startX = W / 2 + dp(50);

        line.setStrokeWidth(dp(2));
        textBig = dp(18);
        textSmall = dp(10);

        cross.reset();                                  /* the d-pad's outline, one plus shape */
        float x = dpadX, y = dpadY, r = dpadR, h2 = arm;
        cross.moveTo(x - h2, y - r);
        cross.lineTo(x + h2, y - r);
        cross.lineTo(x + h2, y - h2);
        cross.lineTo(x + r, y - h2);
        cross.lineTo(x + r, y + h2);
        cross.lineTo(x + h2, y + h2);
        cross.lineTo(x + h2, y + r);
        cross.lineTo(x - h2, y + r);
        cross.lineTo(x - h2, y + h2);
        cross.lineTo(x - r, y + h2);
        cross.lineTo(x - r, y - h2);
        cross.lineTo(x - h2, y - h2);
        cross.close();
    }

    @Override
    protected void onDraw(Canvas g) {
        int m = held;
        float x = dpadX, y = dpadY, r = dpadR, h2 = arm;
        drawRect(g, x - h2, y - r, x + h2, y - h2, (m & Native.UP) != 0);
        drawRect(g, x - h2, y + h2, x + h2, y + r, (m & Native.DOWN) != 0);
        drawRect(g, x - r, y - h2, x - h2, y + h2, (m & Native.LEFT) != 0);
        drawRect(g, x + h2, y - h2, x + r, y + h2, (m & Native.RIGHT) != 0);
        drawRect(g, x - h2, y - h2, x + h2, y + h2, false);
        line.setColor(LINE);
        g.drawPath(cross, line);

        drawButton(g, aX, aY, "A", (m & Native.A) != 0);
        drawButton(g, bX, bY, "B", (m & Native.B) != 0);
        drawPill(g, selX, "SELECT", (m & Native.SELECT) != 0);
        drawPill(g, startX, "START", (m & Native.START) != 0);
    }

    private void drawRect(Canvas g, float l, float t, float rr, float b, boolean pressed) {
        fill.setColor(pressed ? PRESSED_FILL : FILL);
        g.drawRect(l, t, rr, b, fill);
    }

    private void drawButton(Canvas g, float cx, float cy, String label, boolean pressed) {
        fill.setColor(pressed ? PRESSED_FILL : FILL);
        g.drawCircle(cx, cy, btnR, fill);
        line.setColor(pressed ? PRESSED_LINE : LINE);
        g.drawCircle(cx, cy, btnR, line);
        text.setColor(pressed ? PRESSED_LINE : LABEL);
        text.setTextSize(textBig);
        g.drawText(label, cx, cy - (text.descent() + text.ascent()) / 2, text);
    }

    private void drawPill(Canvas g, float cx, String label, boolean pressed) {
        rect.set(cx - pillW / 2, pillY - pillH / 2, cx + pillW / 2, pillY + pillH / 2);
        fill.setColor(pressed ? PRESSED_FILL : FILL);
        g.drawRoundRect(rect, pillH / 2, pillH / 2, fill);
        line.setColor(pressed ? PRESSED_LINE : LINE);
        g.drawRoundRect(rect, pillH / 2, pillH / 2, line);
        text.setColor(pressed ? PRESSED_LINE : LABEL);
        text.setTextSize(textSmall);
        g.drawText(label, cx, pillY - (text.descent() + text.ascent()) / 2, text);
    }

    /* ---------------------------------------------------------------- */
    /* Touch                                                              */
    /* ---------------------------------------------------------------- */

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        int i = e.getActionIndex();
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
            case MotionEvent.ACTION_POINTER_DOWN: {
                int c = hit(e.getX(i), e.getY(i));
                if (c == NONE) return false;            /* not ours: the game gets it */
                int id = e.getPointerId(i);
                owner.put(id, c);
                hold(id, c, e.getX(i), e.getY(i));
                return true;
            }
            case MotionEvent.ACTION_MOVE:
                for (int p = 0; p < e.getPointerCount(); p++) {
                    int id = e.getPointerId(p);
                    int c = owner.get(id, NONE);
                    if (c != NONE) hold(id, c, e.getX(p), e.getY(p));
                }
                return owner.size() > 0;
            case MotionEvent.ACTION_POINTER_UP:
                owner.delete(e.getPointerId(i));
                bits.delete(e.getPointerId(i));
                sync();
                return owner.size() > 0;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL: {
                boolean had = owner.size() > 0;
                owner.clear();
                bits.clear();
                sync();
                return had;
            }
            default:
                return false;
        }
    }

    /** The control under (x, y), or NONE. */
    private int hit(float x, float y) {
        if (d2(x, y, dpadX, dpadY) <= sq(dpadR + slop)) return DPAD;
        if (d2(x, y, aX, aY) <= sq(btnR + slop)) return A;
        if (d2(x, y, bX, bY) <= sq(btnR + slop)) return B;
        if (inPill(x, y, selX)) return SELECT;
        if (inPill(x, y, startX)) return START;
        return NONE;
    }

    private boolean inPill(float x, float y, float cx) {
        return Math.abs(x - cx) <= pillW / 2 + slop && Math.abs(y - pillY) <= pillH / 2 + slop;
    }

    /** Sets the buttons a finger holds, from where it is now. */
    private void hold(int id, int control, float x, float y) {
        bits.put(id, bitsAt(control, x, y));
        sync();
    }

    private int bitsAt(int control, float x, float y) {
        switch (control) {
            case DPAD: return dpadBits(x, y);
            case A: return Native.A;
            case B: return Native.B;
            case START: return Native.START;
            case SELECT: return Native.SELECT;
            default: return 0;
        }
    }

    /* the d-pad: a cardinal direction in its own zone, both neighbours between */
    private int dpadBits(float x, float y) {
        float ax = x - dpadX, ay = y - dpadY;
        if (ax * ax + ay * ay < dead * dead) return 0;
        int m = 0;
        if (Math.abs(ax) >= K * Math.abs(ay)) m |= ax < 0 ? Native.LEFT : Native.RIGHT;
        if (Math.abs(ay) >= K * Math.abs(ax)) m |= ay < 0 ? Native.UP : Native.DOWN;
        return m;
    }

    private void sync() {
        int m = 0;
        for (int k = 0; k < bits.size(); k++) m |= bits.valueAt(k);
        held = m;
        invalidate();
    }

    private static float d2(float x1, float y1, float x2, float y2) {
        float dx = x1 - x2, dy = y1 - y2;
        return dx * dx + dy * dy;
    }

    private static float sq(float v) {
        return v * v;
    }
}
