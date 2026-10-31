package com.cjxh.filetransfer;

import android.app.Activity;
import android.app.Dialog;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.Switch;
import android.widget.TextView;

/**
 * 统一风格的弹窗：圆角卡片 + 图标 + 标题 + 说明 + 选项行 / 开关 / 自定义内容 + 底部按钮。
 * 用来替换系统默认的 AlertDialog，让所有弹窗看起来是一家人。
 */
final class Sheet {
    interface Toggle {
        void on(boolean checked);
    }

    private final Activity a;
    private final LinearLayout box;
    private final LinearLayout body;
    private LinearLayout buttons;
    private final Dialog dialog;

    Sheet(Activity activity) {
        a = activity;
        dialog = new Dialog(a);
        box = new LinearLayout(a);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setBackgroundResource(R.drawable.card_bg);
        box.setPadding(dp(22), dp(24), dp(22), dp(18));
        body = new LinearLayout(a);
        body.setOrientation(LinearLayout.VERTICAL);
    }

    /** 顶部的圆形图标 */
    Sheet icon(int res, int colorRes, int bgRes) {
        FrameLayout c = new FrameLayout(a);
        c.setBackgroundResource(bgRes);
        ImageView img = new ImageView(a);
        img.setImageResource(res);
        img.setImageTintList(ColorStateList.valueOf(a.getColor(colorRes)));
        c.addView(img, new FrameLayout.LayoutParams(dp(26), dp(26), Gravity.CENTER));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(52), dp(52));
        lp.bottomMargin = dp(14);
        box.addView(c, lp);
        return this;
    }

    Sheet title(String text) {
        TextView t = text(text, 20, R.color.text);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        box.addView(t);
        return this;
    }

    Sheet message(String text) {
        TextView t = text(text, 14, R.color.muted);
        t.setLineSpacing(dp(3), 1);
        t.setPadding(0, dp(6), 0, 0);
        box.addView(t);
        return this;
    }

    /** 自定义内容（输入框、列表等） */
    Sheet view(View v) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = dp(16);
        body.addView(v, lp);
        return this;
    }

    /** 可点的选项行：图标 + 文字 + 可选的说明，危险操作用红色 */
    Sheet option(int iconRes, String text, String sub, boolean danger, Runnable onClick) {
        LinearLayout row = row(iconRes, text, sub, danger);
        row.setOnClickListener(v -> {
            dialog.dismiss();
            onClick.run();
        });
        addRow(row);
        return this;
    }

    /** 带开关的选项行，点整行切换 */
    Sheet toggle(int iconRes, String text, String sub, boolean checked, Toggle onChange) {
        LinearLayout row = row(iconRes, text, sub, false);
        Switch sw = new Switch(a);
        sw.setChecked(checked);
        sw.setClickable(false);
        sw.setThumbTintList(ColorStateList.valueOf(Color.WHITE));
        sw.setTrackTintList(new ColorStateList(
                new int[][]{{android.R.attr.state_checked}, {}},
                new int[]{a.getColor(R.color.accent), a.getColor(R.color.faint)}));
        row.addView(sw);
        row.setOnClickListener(v -> {
            sw.setChecked(!sw.isChecked());
            onChange.on(sw.isChecked());
        });
        addRow(row);
        return this;
    }

    /** 底部按钮：左边次要（浅色），右边主要（实心，danger 时为红色）；任一为 null 则不显示 */
    Sheet buttons(String secondary, Runnable onSecondary, String primary, Runnable onPrimary, boolean danger) {
        buttons = new LinearLayout(a);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        if (secondary != null) {
            TextView b = pill(secondary, R.drawable.pill_soft, a.getColor(R.color.accent));
            b.setOnClickListener(v -> {
                dialog.dismiss();
                if (onSecondary != null) onSecondary.run();
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(46), 1);
            if (primary != null) lp.rightMargin = dp(10);
            buttons.addView(b, lp);
        }
        if (primary != null) {
            TextView b = pill(primary, danger ? R.drawable.pill_danger : R.drawable.pill_primary, Color.WHITE);
            b.setOnClickListener(v -> {
                dialog.dismiss();
                if (onPrimary != null) onPrimary.run();
            });
            buttons.addView(b, new LinearLayout.LayoutParams(0, dp(46), 1));
        }
        return this;
    }

    Sheet cancelable(boolean c) {
        dialog.setCancelable(c);
        dialog.setCanceledOnTouchOutside(c);
        return this;
    }

    Dialog show() {
        box.addView(body);
        if (buttons != null) {
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
            lp.topMargin = dp(20);
            box.addView(buttons, lp);
        }
        FrameLayout wrap = new FrameLayout(a);
        wrap.setPadding(dp(20), 0, dp(20), 0);
        wrap.addView(box, new FrameLayout.LayoutParams(-1, -2));
        dialog.setContentView(wrap);
        dialog.getWindow().setBackgroundDrawableResource(android.R.color.transparent);
        dialog.getWindow().setLayout(-1, -2);
        dialog.show();
        return dialog;
    }

    Dialog dialog() {
        return dialog;
    }

    // ---------- 内部 ----------

    private void addRow(View row) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = body.getChildCount() == 0 ? dp(16) : dp(8);
        body.addView(row, lp);
    }

    private LinearLayout row(int iconRes, String text, String sub, boolean danger) {
        LinearLayout row = new LinearLayout(a);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setBackgroundResource(R.drawable.bubble_bg);
        row.setPadding(dp(14), dp(12), dp(14), dp(12));
        row.setMinimumHeight(dp(56));
        int color = a.getColor(danger ? R.color.danger : R.color.accent);
        ImageView img = new ImageView(a);
        img.setImageResource(iconRes);
        img.setImageTintList(ColorStateList.valueOf(color));
        row.addView(img, new LinearLayout.LayoutParams(dp(22), dp(22)));
        LinearLayout col = new LinearLayout(a);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(dp(14), 0, dp(8), 0);
        TextView t = text(text, 16, danger ? R.color.danger : R.color.text);
        col.addView(t);
        if (sub != null) {
            TextView s = text(sub, 12, R.color.muted);
            s.setPadding(0, dp(2), 0, 0);
            col.addView(s);
        }
        row.addView(col, new LinearLayout.LayoutParams(0, -2, 1));
        return row;
    }

    private TextView pill(String text, int bg, int color) {
        TextView b = new TextView(a);
        b.setText(text);
        b.setGravity(Gravity.CENTER);
        b.setTextSize(15);
        b.setTypeface(Typeface.DEFAULT_BOLD);
        b.setTextColor(color);
        b.setBackgroundResource(bg);
        b.setSingleLine(true);
        b.setEllipsize(TextUtils.TruncateAt.END);
        return b;
    }

    private TextView text(String s, int sp, int colorRes) {
        TextView t = new TextView(a);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(a.getColor(colorRes));
        return t;
    }

    private int dp(int v) {
        return Math.round(v * a.getResources().getDisplayMetrics().density);
    }
}
