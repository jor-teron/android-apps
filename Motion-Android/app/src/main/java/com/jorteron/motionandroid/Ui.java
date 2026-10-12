package com.jorteron.motionandroid;

import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Tiny helpers for the code-built dark UI. */
final class Ui {
    static final int BG = 0xFF121212, CARD = 0xFF1E1E1E, TEXT = 0xFFEEEEEE, DIM = 0xFF9E9E9E, ACCENT = 0xFFFF9800;

    static int dp(Context c, float v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, c.getResources().getDisplayMetrics());
    }

    static TextView text(Context c, String s, float sp, int color) {
        TextView t = new TextView(c);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(color);
        t.setPadding(0, dp(c, 4), 0, dp(c, 4));
        return t;
    }

    static Button button(Context c, String s, int color) {
        Button b = new Button(c);
        b.setText(s);
        b.setAllCaps(false);
        b.setTextColor(Color.BLACK);
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(dp(c, 10));
        b.setBackground(g);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, dp(c, 52));
        lp.topMargin = dp(c, 8);
        b.setLayoutParams(lp);
        b.setGravity(Gravity.CENTER);
        return b;
    }

    static LinearLayout column(Context c) {
        LinearLayout l = new LinearLayout(c);
        l.setOrientation(LinearLayout.VERTICAL);
        int p = dp(c, 16);
        l.setPadding(p, p, p, p);
        l.setBackgroundColor(BG);
        return l;
    }
}
