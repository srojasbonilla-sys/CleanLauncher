package com.simple.carlauncher;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.graphics.SweepGradient;
import android.graphics.drawable.Drawable;
import android.view.MotionEvent;
import android.view.View;

/**
 * Round chrome knob like the ones on a turntable: knurled dark edge, brushed metal cap with the
 * icon engraved in the middle. The main knob (play) has a glowing blue LED ring. It sinks a
 * little and the light dims while pressed.
 */
final class KnobButton extends View {

    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final float d;
    private final boolean primary;
    private Drawable icon;
    private boolean pressed;

    KnobButton(Context c, int iconRes, boolean primary, Runnable action) {
        super(c);
        d = getResources().getDisplayMetrics().density;
        this.primary = primary;
        setIcon(iconRes);
        setClickable(true);
        setOnClickListener(v -> action.run());
        if (primary) setLayerType(LAYER_TYPE_SOFTWARE, null); // for the LED glow
    }

    void setIcon(int res) {
        icon = getContext().getDrawable(res).mutate();
        icon.setTint(0xFF1C1E24);
        invalidate();
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        if (e.getAction() == MotionEvent.ACTION_DOWN) {
            pressed = true;
            invalidate();
        } else if (e.getAction() == MotionEvent.ACTION_UP || e.getAction() == MotionEvent.ACTION_CANCEL) {
            pressed = false;
            invalidate();
        }
        return super.onTouchEvent(e);
    }

    @Override
    protected void onDraw(Canvas c) {
        float w = getWidth(), h = getHeight();
        float cx = w / 2f, cy = h / 2f;
        float r = Math.min(w, h) / 2f - (primary ? 7 * d : 2 * d);
        float sink = pressed ? 1.5f * d : 0;

        // blue LED ring around the main knob
        if (primary) {
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(3 * d);
            p.setColor(pressed ? 0x8800B0FF : 0xFF00B0FF);
            p.setShadowLayer(8 * d, 0, 0, 0xFF00B0FF);
            c.drawCircle(cx, cy, r + 4 * d, p);
            p.clearShadowLayer();
            p.setStyle(Paint.Style.FILL);
        }
        // shadow
        p.setColor(0x99000000);
        c.drawCircle(cx + 1.5f * d, cy + 2.5f * d, r, p);
        // knurled edge: dark ring with fine ridges
        p.setShader(new SweepGradient(cx, cy, new int[]{0xFF3A3C44, 0xFF15161A, 0xFF4A4C54, 0xFF15161A, 0xFF3A3C44}, null));
        c.drawCircle(cx, cy + sink, r, p);
        p.setShader(null);
        p.setColor(0x33FFFFFF);
        p.setStrokeWidth(Math.max(1f, 0.8f * d));
        int ridges = 48;
        for (int i = 0; i < ridges; i++) {
            double a = Math.toRadians(i * 360.0 / ridges);
            float cos = (float) Math.cos(a), sin = (float) Math.sin(a);
            c.drawLine(cx + cos * r * 0.84f, cy + sink + sin * r * 0.84f, cx + cos * r * 0.98f, cy + sink + sin * r * 0.98f, p);
        }
        // brushed chrome cap
        float cap = r * 0.8f;
        p.setShader(new RadialGradient(cx - cap * 0.35f, cy + sink - cap * 0.4f, cap * 1.5f,
                pressed ? new int[]{0xFFD0D2DA, 0xFF9A9CA6, 0xFF55575F} : new int[]{0xFFFFFFFF, 0xFFC4C6CE, 0xFF6E7078},
                new float[]{0f, 0.55f, 1f}, Shader.TileMode.CLAMP));
        c.drawCircle(cx, cy + sink, cap, p);
        p.setShader(new SweepGradient(cx, cy + sink, new int[]{0x00FFFFFF, 0x26FFFFFF, 0x00FFFFFF, 0x1A000000,
                0x00FFFFFF, 0x26FFFFFF, 0x00FFFFFF, 0x1A000000, 0x00FFFFFF}, null)); // brushed shine
        c.drawCircle(cx, cy + sink, cap, p);
        p.setShader(null);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(Math.max(1f, d));
        p.setColor(0x66000000);
        c.drawCircle(cx, cy + sink, cap, p);
        p.setStyle(Paint.Style.FILL);
        // engraved icon
        if (icon != null) {
            int s = Math.round(cap * 1.05f);
            int l = Math.round(cx - s / 2f), t = Math.round(cy + sink - s / 2f);
            icon.setTint(0x66FFFFFF); // light edge under the engraving
            icon.setBounds(l, t + Math.round(d), l + s, t + s + Math.round(d));
            icon.draw(c);
            icon.setTint(primary ? 0xFF0D47A1 : 0xFF1C1E24);
            icon.setBounds(l, t, l + s, t + s);
            icon.draw(c);
        }
    }
}
