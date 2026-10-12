package com.speedanalyst.commute;

import android.content.Context;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.content.res.ColorStateList;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Programmatic view helpers + colour palette. */
public final class Ui {
    private Ui() {
    }

    public static final int BG = 0xFF0F172A;
    public static final int CARD = 0xFF1E293B;
    public static final int CARD2 = 0xFF273449;
    public static final int TEXT = 0xFFF1F5F9;
    public static final int MUTED = 0xFF94A3B8;
    public static final int GREEN = 0xFF22C55E;
    public static final int BLUE = 0xFF38BDF8;
    public static final int AMBER = 0xFFF59E0B;
    public static final int RED = 0xFFEF4444;
    public static final int PURPLE = 0xFFA78BFA;

    public static int dp(Context c, float v) {
        return Math.round(v * c.getResources().getDisplayMetrics().density);
    }

    public static GradientDrawable round(int color, float radiusPx) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(radiusPx);
        return g;
    }

    public static LinearLayout vbox(Context c) {
        LinearLayout l = new LinearLayout(c);
        l.setOrientation(LinearLayout.VERTICAL);
        return l;
    }

    public static LinearLayout hbox(Context c) {
        LinearLayout l = new LinearLayout(c);
        l.setOrientation(LinearLayout.HORIZONTAL);
        l.setGravity(Gravity.CENTER_VERTICAL);
        return l;
    }

    public static LinearLayout card(Context c) {
        LinearLayout l = vbox(c);
        l.setBackground(round(CARD, dp(c, 16)));
        int p = dp(c, 16);
        l.setPadding(p, p, p, p);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(c, 12);
        l.setLayoutParams(lp);
        return l;
    }

    public static TextView text(Context c, String s, float sp, int color, boolean bold) {
        TextView t = new TextView(c);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(color);
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setLineSpacing(0, 1.15f);
        return t;
    }

    public static TextView title(Context c, String s) {
        TextView t = text(c, s, 16, TEXT, true);
        t.setPadding(0, 0, 0, dp(c, 8));
        return t;
    }

    public static TextView body(Context c, String s) {
        return text(c, s, 14, TEXT, false);
    }

    public static TextView muted(Context c, String s) {
        return text(c, s, 13, MUTED, false);
    }

    public static TextView button(Context c, String label, int color, View.OnClickListener l) {
        TextView b = text(c, label, 15, color == TEXT ? BG : 0xFFFFFFFF, true);
        if (color == GREEN || color == AMBER) b.setTextColor(0xFF06210F);
        b.setGravity(Gravity.CENTER);
        int ph = dp(c, 16), pv = dp(c, 12);
        b.setPadding(ph, pv, ph, pv);
        b.setBackground(new RippleDrawable(ColorStateList.valueOf(0x55FFFFFF), round(color, dp(c, 12)), null));
        b.setClickable(true);
        b.setOnClickListener(l);
        return b;
    }

    public static LinearLayout.LayoutParams matchWrap(Context c, int topMarginDp) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(c, topMarginDp);
        return lp;
    }

    public static LinearLayout.LayoutParams weight(Context c, float w, int marginDp) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, w);
        lp.leftMargin = dp(c, marginDp);
        lp.rightMargin = dp(c, marginDp);
        return lp;
    }

    /** Small stat tile: big value + label. */
    public static LinearLayout stat(Context c, String value, String label, int color) {
        LinearLayout l = vbox(c);
        l.setBackground(round(CARD2, dp(c, 12)));
        int p = dp(c, 10);
        l.setPadding(p, p, p, p);
        l.setGravity(Gravity.CENTER);
        TextView v = text(c, value, 18, color, true);
        v.setGravity(Gravity.CENTER);
        TextView t = text(c, label, 11, MUTED, false);
        t.setGravity(Gravity.CENTER);
        l.addView(v);
        l.addView(t);
        return l;
    }

    /** Row of equally sized stat tiles. */
    public static LinearLayout statRow(Context c, View... tiles) {
        LinearLayout row = hbox(c);
        row.setLayoutParams(matchWrap(c, 8));
        for (View v : tiles) row.addView(v, weight(c, 1, 3));
        return row;
    }

    /** Label on the left, value on the right. */
    public static LinearLayout kv(Context c, String k, String v) {
        LinearLayout row = hbox(c);
        row.setPadding(0, dp(c, 5), 0, dp(c, 5));
        TextView kt = muted(c, k);
        TextView vt = text(c, v, 14, TEXT, true);
        vt.setGravity(Gravity.END);
        row.addView(kt, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        row.addView(vt);
        return row;
    }

    public static View divider(Context c) {
        View v = new View(c);
        v.setBackgroundColor(0x22FFFFFF);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1);
        lp.topMargin = dp(c, 8);
        lp.bottomMargin = dp(c, 8);
        v.setLayoutParams(lp);
        return v;
    }

    /** Pill-style segmented toggle. Returns the container; listener receives index. */
    public interface OnPick {
        void pick(int index);
    }

    public static LinearLayout segmented(final Context c, String[] items, int selected, final OnPick l) {
        LinearLayout row = hbox(c);
        row.setBackground(round(CARD2, dp(c, 12)));
        int p = dp(c, 4);
        row.setPadding(p, p, p, p);
        for (int i = 0; i < items.length; i++) {
            final int idx = i;
            TextView t = text(c, items[i], 14, i == selected ? BG : MUTED, true);
            t.setGravity(Gravity.CENTER);
            t.setPadding(0, dp(c, 9), 0, dp(c, 9));
            if (i == selected) t.setBackground(round(TEXT, dp(c, 9)));
            t.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    l.pick(idx);
                }
            });
            row.addView(t, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        }
        return row;
    }
}
