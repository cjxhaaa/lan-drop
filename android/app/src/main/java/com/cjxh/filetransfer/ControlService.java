package com.cjxh.filetransfer;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.graphics.Path;
import android.graphics.Point;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Display;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

import org.json.JSONObject;

/**
 * 让电脑操作手机：把电脑发来的点击、滑动、返回键、文字转成手机上的手势和输入。
 * 需要用户在系统「无障碍」设置里开启。
 */
public class ControlService extends AccessibilityService {
    private static volatile ControlService instance;
    private static final Handler main = new Handler(Looper.getMainLooper());

    static boolean enabled() {
        return instance != null;
    }

    /** 投屏服务收到电脑的操作后调用（任意线程） */
    static void dispatch(JSONObject e) {
        ControlService s = instance;
        if (s != null) main.post(() -> s.handle(e));
    }

    @Override
    protected void onServiceConnected() {
        instance = this;
    }

    @Override
    public boolean onUnbind(android.content.Intent intent) {
        instance = null;
        return super.onUnbind(intent);
    }

    @Override
    public void onDestroy() {
        instance = null;
        super.onDestroy();
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
    }

    @Override
    public void onInterrupt() {
    }

    private Point screen() {
        Point p = new Point();
        Display d = getSystemService(WindowManager.class).getDefaultDisplay();
        d.getRealSize(p);
        return p;
    }

    private void handle(JSONObject e) {
        Point s = screen();
        float x = (float) e.optDouble("x", 0.5) * s.x, y = (float) e.optDouble("y", 0.5) * s.y;
        switch (e.optString("t")) {
            case "tap":
                stroke(line(x, y, x, y), 40);
                break;
            case "long":
                stroke(line(x, y, x, y), 700);
                break;
            case "swipe":
                stroke(line((float) e.optDouble("x1") * s.x, (float) e.optDouble("y1") * s.y,
                        (float) e.optDouble("x2") * s.x, (float) e.optDouble("y2") * s.y), e.optLong("ms", 300));
                break;
            case "scroll": {
                // 滚轮向下 = 内容往下翻 = 手指往上划
                float dist = s.y * 0.28f * (e.optInt("dy") > 0 ? -1 : 1);
                float y2 = Math.max(1, Math.min(s.y - 1, y + dist));
                stroke(line(x, y, x, y2), 220);
                break;
            }
            case "global":
                switch (e.optString("a")) {
                    case "back": performGlobalAction(GLOBAL_ACTION_BACK); break;
                    case "home": performGlobalAction(GLOBAL_ACTION_HOME); break;
                    case "recents": performGlobalAction(GLOBAL_ACTION_RECENTS); break;
                }
                break;
            case "text":
                editFocused(e.optString("s"), false);
                break;
            case "key":
                if ("backspace".equals(e.optString("k"))) editFocused("", true);
                else if ("enter".equals(e.optString("k"))) pressEnter();
                break;
        }
    }

    private static Path line(float x1, float y1, float x2, float y2) {
        Path p = new Path();
        p.moveTo(Math.max(0, x1), Math.max(0, y1));
        p.lineTo(Math.max(0, x2), Math.max(0, y2));
        return p;
    }

    private void stroke(Path path, long ms) {
        GestureDescription g = new GestureDescription.Builder()
                .addStroke(new GestureDescription.StrokeDescription(path, 0, Math.max(1, Math.min(ms, 10_000))))
                .build();
        dispatchGesture(g, null, null);
    }

    /** 往当前获得焦点的输入框里追加文字，或删掉最后一个字 */
    private void editFocused(String add, boolean backspace) {
        AccessibilityNodeInfo node = findFocus(AccessibilityNodeInfo.FOCUS_INPUT);
        if (node == null || !node.isEditable()) return;
        CharSequence cur = node.isShowingHintText() || node.getText() == null ? "" : node.getText();
        String text = cur.toString();
        if (backspace) {
            if (text.isEmpty()) return;
            text = text.substring(0, text.offsetByCodePoints(text.length(), -1));
        } else {
            text = text + add;
        }
        Bundle args = new Bundle();
        args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text);
        node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args);
        Bundle sel = new Bundle();
        sel.putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, text.length());
        sel.putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, text.length());
        node.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, sel);
    }

    private void pressEnter() {
        AccessibilityNodeInfo node = findFocus(AccessibilityNodeInfo.FOCUS_INPUT);
        if (node != null && android.os.Build.VERSION.SDK_INT >= 30) {
            node.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.getId());
        }
    }
}
