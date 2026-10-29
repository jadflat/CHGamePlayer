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
    private static final int NONE = 0, DPAD = 1, A = 2, B = 3, START = 4, SELECT = 5, MENU = 6;
    /* the groups the layout editor moves and resizes */
    static final int G_DPAD = 0, G_A = 1, G_B = 2, G_PILLS = 3, G_MENU = 4, GROUPS = 5;
    static final String[] GROUP_NAMES = { "D-pad", "A", "B", "Start / Select", "Menu" };
    private static final String[] GROUP_KEYS = { "dpad", "a", "b", "pills", "menu" };
    static final float MIN_SCALE = 0.5f, MAX_SCALE = 2f;
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
    private float aX, aY, bX, bY;
    private float pillW, pillH, pillY, selX, startX;
    private float menuX, menuY, menuW, menuH;
    private float btnRA, btnRB, textA, textB, textPill, textMenu;

    /* the layout: each group's offset (fractions of the view's width and height) and size, per orientation */
    private final float[] fx = new float[GROUPS], fy = new float[GROUPS], gs = new float[GROUPS];
    private boolean vibrate;
    private boolean editing;
    private int selected = G_DPAD;
    private Runnable onMenu;
    private Runnable onSelect;
    private float lastX, lastY, pinch;
    private boolean dragging;

    /* which control each finger is on, and the buttons it holds */
    private final SparseIntArray owner = new SparseIntArray();
    private final SparseIntArray bits = new SparseIntArray();
    private volatile int held;

    TouchControls(Context c) {
        super(c);
        loadLayout();
        line.setStyle(Paint.Style.STROKE);
        fill.setStyle(Paint.Style.FILL);
        text.setTypeface(Ui.MEDIUM);
        text.setTextAlign(Paint.Align.CENTER);
    }

    /* ---------------------------------------------------------------- */
    /* The layout, kept in the preferences                                */
    /* ---------------------------------------------------------------- */

    private boolean portrait() {
        return getResources().getConfiguration().orientation == android.content.res.Configuration.ORIENTATION_PORTRAIT;
    }

    private String prefix() {
        return portrait() ? "tcp:" : "tcl:";
    }

    private android.content.SharedPreferences prefs() {
        return getContext().getSharedPreferences(MainActivity.PREFS, Context.MODE_PRIVATE);
    }

    /** Reads this orientation's layout, the opacity and the vibrate switch. */
    void loadLayout() {
        android.content.SharedPreferences p = prefs();
        for (int g = 0; g < GROUPS; g++) {
            fx[g] = 0;
            fy[g] = 0;
            gs[g] = portrait() && g <= G_B ? 1.15f : 1;     /* portrait: the d-pad and A/B a bit bigger */
            String v = p.getString(prefix() + GROUP_KEYS[g], null);
            if (v == null) continue;
            String[] a = v.split(",");
            try {
                fx[g] = Math.max(-1f, Math.min(1f, Float.parseFloat(a[0])));
                fy[g] = Math.max(-1f, Math.min(1f, Float.parseFloat(a[1])));
                gs[g] = Math.max(MIN_SCALE, Math.min(MAX_SCALE, Float.parseFloat(a[2])));
            } catch (RuntimeException e) {
                fx[g] = 0; fy[g] = 0; gs[g] = portrait() && g <= G_B ? 1.15f : 1;
            }
        }
        vibrate = Options.vibrate(p);
        setAlpha(Options.touchOpacity(p) / 100f);
    }

    private void saveLayout() {
        android.content.SharedPreferences.Editor e = prefs().edit();
        for (int g = 0; g < GROUPS; g++)
            e.putString(prefix() + GROUP_KEYS[g], fx[g] + "," + fy[g] + "," + gs[g]);
        e.apply();
    }

    /** Back to the default places and sizes for this orientation. */
    void resetLayout() {
        android.content.SharedPreferences.Editor e = prefs().edit();
        for (int g = 0; g < GROUPS; g++) e.remove(prefix() + GROUP_KEYS[g]);
        e.apply();
        loadLayout();
        layout();
        invalidate();
    }

    /** The layout editor: every group drawn and movable, no buttons pressed. */
    void setEditing(boolean on, Runnable selectionChanged) {
        editing = on;
        onSelect = selectionChanged;
        invalidate();
    }

    /** Tapping the menu button in the game. */
    void setOnMenu(Runnable r) {
        onMenu = r;
    }

    String selectedName() {
        return GROUP_NAMES[selected];
    }

    /** The picture's side in a game for a w x h screen (the same as GameActivity works it out). */
    static int pictureSide(boolean portrait, boolean fill, int w, int h) {
        if (portrait) {
            int s = fill ? Math.min(w, h) : w >= 128 ? w / 128 * 128 : Math.min(w, h);
            return s <= h ? s : Math.min(w, h);
        }
        int side = Math.min(w, h);
        return fill ? side : Math.max(1, side / 128) * 128;
    }

    /** Makes the selected group bigger or smaller (the editor's + and -). */
    void resizeSelected(float factor) {
        gs[selected] = Math.max(MIN_SCALE, Math.min(MAX_SCALE, gs[selected] * factor));
        layout();
        invalidate();
        saveLayout();
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
        loadLayout();
        layout();
    }

    private void layout() {
        float W = getWidth(), H = getHeight();
        if (W == 0 || H == 0) return;
        /* portrait: the pad sits in the space under the picture, so its parts shrink to fit it */
        unit = portrait() ? Math.min(1.3f, Math.min(W / Ui.dp(getContext(), 340), H / Ui.dp(getContext(), 220))) : 1f;
        boolean pt = portrait();
        float cy0 = H * 0.44f;                          /* portrait: the controls' middle, a bit above centre */
        dpadR = dp(66) * gs[G_DPAD];
        arm = dp(21) * gs[G_DPAD];
        dead = dp(12) * gs[G_DPAD];
        slop = dp(10);
        dpadX = (pt ? W * 0.26f : dp(20) + dp(66)) + fx[G_DPAD] * W;
        dpadY = (pt ? cy0 : H - dp(20) - dp(66)) + fy[G_DPAD] * H;

        btnRA = dp(30) * gs[G_A];
        btnRB = dp(30) * gs[G_B];
        aX = W - dp(22) - dp(30) + fx[G_A] * W;         /* A: to the right and higher */
        aY = (pt ? cy0 - dp(32) : H - dp(22) - dp(30) - dp(40)) + fy[G_A] * H;
        bX = W - dp(22) - dp(30) - dp(68) + fx[G_B] * W; /* B: lower-left of A */
        bY = (pt ? cy0 + dp(32) : H - dp(22) - dp(30)) + fy[G_B] * H;

        float sp = gs[G_PILLS];
        pillW = dp(64) * sp;
        pillH = dp(22) * sp;
        pillY = (pt ? Math.min(H - dp(16), cy0 + dp(66) * gs[G_DPAD] + dp(34)) : H - dp(26)) + fy[G_PILLS] * H;
        selX = W / 2 - dp(50) * sp + fx[G_PILLS] * W;
        startX = W / 2 + dp(50) * sp + fx[G_PILLS] * W;

        menuW = dp(64) * gs[G_MENU];
        menuH = dp(22) * gs[G_MENU];
        menuX = W / 2 + fx[G_MENU] * W;
        menuY = dp(24) + fy[G_MENU] * H;

        line.setStrokeWidth(dp(2));
        textA = dp(18) * gs[G_A];
        textB = dp(18) * gs[G_B];
        textPill = dp(10) * sp;
        textMenu = dp(10) * gs[G_MENU];

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

        drawButton(g, aX, aY, btnRA, textA, "A", (m & Native.A) != 0);
        drawButton(g, bX, bY, btnRB, textB, "B", (m & Native.B) != 0);
        drawPill(g, selX, pillY, pillW, pillH, textPill, "SELECT", (m & Native.SELECT) != 0);
        drawPill(g, startX, pillY, pillW, pillH, textPill, "START", (m & Native.START) != 0);
        drawPill(g, menuX, menuY, menuW, menuH, textMenu, "MENU", false);
        if (editing) {
            /* the selected group, outlined in the app's yellow */
            line.setColor(Ui.PRIMARY);
            float o = dp(6);
            switch (selected) {
                case G_DPAD: g.drawRect(dpadX - dpadR - o, dpadY - dpadR - o, dpadX + dpadR + o, dpadY + dpadR + o, line); break;
                case G_A: g.drawCircle(aX, aY, btnRA + o, line); break;
                case G_B: g.drawCircle(bX, bY, btnRB + o, line); break;
                case G_PILLS: g.drawRect(selX - pillW / 2 - o, pillY - pillH / 2 - o, startX + pillW / 2 + o, pillY + pillH / 2 + o, line); break;
                default: g.drawRect(menuX - menuW / 2 - o, menuY - menuH / 2 - o, menuX + menuW / 2 + o, menuY + menuH / 2 + o, line);
            }
        }
    }

    private void drawRect(Canvas g, float l, float t, float rr, float b, boolean pressed) {
        fill.setColor(pressed ? PRESSED_FILL : FILL);
        g.drawRect(l, t, rr, b, fill);
    }

    private void drawButton(Canvas g, float cx, float cy, float rad, float size, String label, boolean pressed) {
        fill.setColor(pressed ? PRESSED_FILL : FILL);
        g.drawCircle(cx, cy, rad, fill);
        line.setColor(pressed ? PRESSED_LINE : LINE);
        g.drawCircle(cx, cy, rad, line);
        text.setColor(pressed ? PRESSED_LINE : LABEL);
        text.setTextSize(size);
        g.drawText(label, cx, cy - (text.descent() + text.ascent()) / 2, text);
    }

    private void drawPill(Canvas g, float cx, float cy, float w, float h, float size, String label, boolean pressed) {
        rect.set(cx - w / 2, cy - h / 2, cx + w / 2, cy + h / 2);
        fill.setColor(pressed ? PRESSED_FILL : FILL);
        g.drawRoundRect(rect, h / 2, h / 2, fill);
        line.setColor(pressed ? PRESSED_LINE : LINE);
        g.drawRoundRect(rect, h / 2, h / 2, line);
        text.setColor(pressed ? PRESSED_LINE : LABEL);
        text.setTextSize(size);
        g.drawText(label, cx, cy - (text.descent() + text.ascent()) / 2, text);
    }

    /* ---------------------------------------------------------------- */
    /* Touch                                                              */
    /* ---------------------------------------------------------------- */

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        if (editing) return editTouch(e);
        int i = e.getActionIndex();
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
            case MotionEvent.ACTION_POINTER_DOWN: {
                int c = hit(e.getX(i), e.getY(i), slop);
                if (c == NONE) return false;            /* not ours: the game gets it */
                int id = e.getPointerId(i);
                owner.put(id, c);
                hold(id, c, e.getX(i), e.getY(i));
                if (c == MENU) buzz();
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
                if (owner.get(e.getPointerId(i), NONE) == MENU && onMenu != null && hit(e.getX(i), e.getY(i), slop) == MENU)
                    onMenu.run();
                owner.delete(e.getPointerId(i));
                bits.delete(e.getPointerId(i));
                sync();
                return owner.size() > 0;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL: {
                boolean had = owner.size() > 0;
                if (e.getActionMasked() == MotionEvent.ACTION_UP && owner.get(e.getPointerId(i), NONE) == MENU
                        && onMenu != null && hit(e.getX(i), e.getY(i), slop) == MENU)
                    onMenu.run();
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
    private int hit(float x, float y, float sl) {
        float more = portrait() ? 0.2f : 0f;               /* portrait: the hit area 20% beyond the drawing */
        if (d2(x, y, dpadX, dpadY) <= sq(dpadR * (1 + more) + sl)) return DPAD;
        if (d2(x, y, aX, aY) <= sq(btnRA * (1 + more) + sl)) return A;
        if (d2(x, y, bX, bY) <= sq(btnRB * (1 + more) + sl)) return B;
        if (inPill(x, y, selX, pillY, pillW, pillH, sl)) return SELECT;
        if (inPill(x, y, startX, pillY, pillW, pillH, sl)) return START;
        if (inPill(x, y, menuX, menuY, menuW, menuH, sl)) return MENU;
        return NONE;
    }

    private static boolean inPill(float x, float y, float cx, float cy, float w, float h, float sl) {
        return Math.abs(x - cx) <= w / 2 + sl && Math.abs(y - cy) <= h / 2 + sl;
    }

    /** A short haptic tick (no permission needed; the system's touch feedback setting is ignored). */
    @SuppressWarnings("deprecation")
    private void buzz() {
        if (vibrate)
            performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY,
                    android.view.HapticFeedbackConstants.FLAG_IGNORE_GLOBAL_SETTING);
    }

    /* ---------------------------------------------------------------- */
    /* Editing: drag a group to move it, pinch to resize                  */
    /* ---------------------------------------------------------------- */

    private int groupOf(int control) {
        switch (control) {
            case DPAD: return G_DPAD;
            case A: return G_A;
            case B: return G_B;
            case START: case SELECT: return G_PILLS;
            default: return G_MENU;
        }
    }

    private boolean editTouch(MotionEvent e) {
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN: {
                int c = hit(e.getX(), e.getY(), dp(14));
                dragging = c != NONE;
                if (c != NONE) {
                    selected = groupOf(c);
                    if (onSelect != null) onSelect.run();
                    invalidate();
                }
                lastX = e.getX();
                lastY = e.getY();
                return true;
            }
            case MotionEvent.ACTION_POINTER_DOWN:
                if (e.getPointerCount() == 2) pinch = spread(e);
                return true;
            case MotionEvent.ACTION_MOVE:
                if (e.getPointerCount() >= 2) {
                    float d = spread(e);
                    if (pinch > 0 && d > 0) {
                        gs[selected] = Math.max(MIN_SCALE, Math.min(MAX_SCALE, gs[selected] * d / pinch));
                        layout();
                        invalidate();
                    }
                    pinch = d;
                } else if (dragging) {
                    moveSelected(e.getX() - lastX, e.getY() - lastY);
                    lastX = e.getX();
                    lastY = e.getY();
                }
                return true;
            case MotionEvent.ACTION_POINTER_UP: {
                pinch = 0;
                /* the finger left behind carries on as a drag from where it is */
                int rest = e.getActionIndex() == 0 ? 1 : 0;
                if (rest < e.getPointerCount()) {
                    lastX = e.getX(rest);
                    lastY = e.getY(rest);
                }
                saveLayout();
                return true;
            }
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                pinch = 0;
                saveLayout();
                return true;
            default:
                return true;
        }
    }

    private static float spread(MotionEvent e) {
        float dx = e.getX(0) - e.getX(1), dy = e.getY(0) - e.getY(1);
        return (float) Math.sqrt(dx * dx + dy * dy);
    }

    /** The selected group by (dx, dy) pixels; its middle stays on the screen. */
    private void moveSelected(float dx, float dy) {
        float W = getWidth(), H = getHeight();
        float cx, cy;
        switch (selected) {
            case G_DPAD: cx = dpadX; cy = dpadY; break;
            case G_A: cx = aX; cy = aY; break;
            case G_B: cx = bX; cy = bY; break;
            case G_PILLS: cx = (selX + startX) / 2; cy = pillY; break;
            default: cx = menuX; cy = menuY;
        }
        if (cx + dx >= 0 && cx + dx <= W) fx[selected] += dx / W;
        if (cy + dy >= 0 && cy + dy <= H) fy[selected] += dy / H;
        layout();
        invalidate();
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
        if ((m & ~held) != 0) buzz();           /* a button newly down */
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
