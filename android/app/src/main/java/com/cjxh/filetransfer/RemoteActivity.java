package com.cjxh.filetransfer;

import android.app.Activity;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.view.inputmethod.BaseInputConnection;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 远程控制电脑：全屏显示电脑画面，手势映射为鼠标操作。
 * 单击 = 左键，长按 = 右键，单指拖动 = 按住左键拖动，双指上下滑 = 滚轮，双指捏合 = 放大画面（放大后双指移动画面）。
 */
public class RemoteActivity extends Activity {
    private static final String SENTINEL = "​"; // 输入框里始终保留一个零宽字符，用来识别退格

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService sender = Executors.newSingleThreadExecutor();
    private final List<JSONObject> pending = new ArrayList<>();
    private Store store;
    private RemoteView screen;
    private TextView status, btnScreen;
    private EditText keyInput;
    private volatile boolean running;
    private volatile HttpURLConnection streamConn;
    private Thread streamThread;
    private volatile int streamId; // 每次重连加一；旧线程退出时不能影响新线程
    private int monitor = -1;
    private int monitorCount = 1;
    private String quality;
    private TextView btnQuality, btnMode, hint;
    private boolean touchpad; // true = 触控板模式（默认），false = 跟手模式（按到哪鼠标到哪）
    private static final String[] QUALITY_KEYS = {"smooth", "hd", "full"};
    private static final String[] QUALITY_NAMES = {"流畅", "高清", "原画"};
    
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        store = new Store(this);
        quality = getSharedPreferences("remote", MODE_PRIVATE).getString("quality", "hd");
        touchpad = getSharedPreferences("remote", MODE_PRIVATE).getBoolean("touchpad", true);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        screen = new RemoteView(this);
        root.addView(screen, new FrameLayout.LayoutParams(-1, -1));

        // 隐藏的输入框：接住手机输入法打的字，转发给电脑
        keyInput = new EditText(this);
        keyInput.setAlpha(0f);
        keyInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        keyInput.setImeOptions(EditorInfo.IME_FLAG_NO_EXTRACT_UI | EditorInfo.IME_FLAG_NO_FULLSCREEN);
        keyInput.setText(SENTINEL);
        keyInput.addTextChangedListener(keyWatcher);
        root.addView(keyInput, new FrameLayout.LayoutParams(dp(2), dp(2)));

        status = new TextView(this);
        status.setTextColor(Color.WHITE);
        status.setTextSize(15);
        status.setGravity(Gravity.CENTER);
        status.setLineSpacing(dp(4), 1);
        status.setPadding(dp(32), 0, dp(32), 0);
        root.addView(status, new FrameLayout.LayoutParams(-1, -2, Gravity.CENTER));

        // 工具栏收在右侧中间，只露一个按钮，避免挡住窗口右上角的关闭按钮和右下角的任务栏
        LinearLayout tools = new LinearLayout(this);
        tools.setOrientation(LinearLayout.VERTICAL);
        tools.setGravity(Gravity.END);
        tools.setVisibility(View.GONE);
        btnMode = barButton(touchpad ? L.t("触控板") : L.t("跟手"), v -> toggleMode());
        tools.addView(btnMode);
        btnQuality = barButton(qualityName(), v -> chooseQuality(v));
        tools.addView(btnQuality);
        tools.addView(barButton(L.t("适应屏幕"), v -> {
            screen.resetZoom();
            flash(L.t("适应屏幕"), 1000);
        }));
        tools.addView(barButton(L.t("键盘"), v -> toggleKeyboard()));
        tools.addView(barButton("Esc", v -> key("esc")));
        tools.addView(barButton("Win", v -> key("win")));
        tools.addView(barButton(L.t("退格"), v -> key("backspace")));
        tools.addView(barButton(L.t("回车"), v -> key("enter")));
        btnScreen = barButton(L.t("屏幕"), v -> switchMonitor());
        btnScreen.setVisibility(View.GONE);
        tools.addView(btnScreen);
        tools.addView(barButton(L.t("退出"), v -> finish()));
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setGravity(Gravity.END);
        panel.addView(tools);
        TextView toggle = barButton(L.t("工具"), null);
        toggle.setOnClickListener(v -> {
            boolean show = tools.getVisibility() != View.VISIBLE;
            tools.setVisibility(show ? View.VISIBLE : View.GONE);
            toggle.setText(show ? L.t("收起") : L.t("工具"));
        });
        panel.addView(toggle);
        FrameLayout.LayoutParams plp = new FrameLayout.LayoutParams(-2, -2, Gravity.END | Gravity.CENTER_VERTICAL);
        plp.rightMargin = dp(8);
        root.addView(panel, plp);

        hint = new TextView(this);
        hint.setTextColor(Color.WHITE);
        hint.setTextSize(13);
        hint.setGravity(Gravity.CENTER);
        hint.setBackgroundResource(R.drawable.remote_btn);
        hint.setPadding(dp(18), dp(8), dp(18), dp(8));
        FrameLayout.LayoutParams hlp = new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        hlp.bottomMargin = dp(16);
        root.addView(hint, hlp);

        setContentView(root);
        immersive();
        loadMonitors();
        showHint();
    }

    @Override
    protected void onResume() {
        super.onResume();
        immersive();
        startStream();
    }

    @Override
    protected void onPause() {
        super.onPause();
        stopStream();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        sender.shutdown();
    }

    private void immersive() {
        if (Build.VERSION.SDK_INT >= 30) {
            getWindow().setDecorFitsSystemWindows(false);
            WindowInsetsController c = getWindow().getInsetsController();
            if (c != null) {
                c.hide(WindowInsets.Type.systemBars());
                c.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
            }
        } else {
            getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                    | View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    | View.SYSTEM_UI_FLAG_LAYOUT_STABLE | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
        }
    }

    private TextView barButton(String text, View.OnClickListener l) {
        TextView b = new TextView(this);
        b.setText(text);
        b.setTextColor(Color.WHITE);
        b.setTextSize(12);
        b.setGravity(Gravity.CENTER);
        b.setBackgroundResource(R.drawable.remote_btn);
        b.setPadding(dp(12), 0, dp(12), 0);
        b.setMinWidth(dp(60));
        if (l != null) b.setOnClickListener(l);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, dp(32));
        lp.topMargin = dp(6);
        b.setLayoutParams(lp);
        return b;
    }

    // ---------- 画面流 ----------

    private void loadMonitors() {
        sender.execute(() -> {
            try {
                HttpURLConnection c = Net.open(store.base() + "/api/remote/info", "GET", store.key());
                JSONObject info = new JSONObject(Net.readBody(c));
                JSONArray list = info.optJSONArray("monitors");
                int n = list == null ? 1 : list.length();
                ui.post(() -> {
                    monitorCount = n;
                    btnScreen.setVisibility(n > 1 ? View.VISIBLE : View.GONE);
                });
            } catch (IOException | JSONException ignored) {
            }
        });
    }

    private void toggleMode() {
        touchpad = !touchpad;
        getSharedPreferences("remote", MODE_PRIVATE).edit().putBoolean("touchpad", touchpad).apply();
        btnMode.setText(touchpad ? L.t("触控板") : L.t("跟手"));
        showHint();
    }

    private final Runnable hideHint = () -> hint.animate().alpha(0f).setDuration(400).start();

    /** 底部短暂显示一行提示 */
    private void flash(String text, long ms) {
        hint.setText(text);
        hint.animate().cancel();
        hint.setAlpha(1f);
        ui.removeCallbacks(hideHint);
        ui.postDelayed(hideHint, ms);
    }

    private void showHint() {
        hint.setText(touchpad
                ? L.t("触控板模式：单指滑动移动鼠标 · 轻点 = 左键 · 双指轻点 = 右键 · 点两下按住滑动 = 拖动 · 双指上下滑 = 滚动 · 双指捏合 = 缩放画面，放大后双指拖动画面")
                : L.t("跟手模式：按到哪鼠标就到哪 · 轻点 = 左键 · 长按后滑动 = 拖动 · 长按松开 / 双指轻点 = 右键 · 双指捏合 = 缩放画面，放大后双指拖动画面"));
        hint.animate().cancel();
        hint.setAlpha(1f);
        ui.removeCallbacks(hideHint);
        ui.postDelayed(hideHint, 5000);
    }

    private String qualityName() {
        for (int i = 0; i < QUALITY_KEYS.length; i++) if (QUALITY_KEYS[i].equals(quality)) return L.t(QUALITY_NAMES[i]);
        return L.t(QUALITY_NAMES[1]);
    }

    private void chooseQuality(View anchor) {
        Sheet sheet = new Sheet(this)
                .icon(R.drawable.ic_hd, R.color.accent, R.drawable.circle_soft)
                .title(L.t("画面清晰度"))
                .message(L.t("越清晰，每秒画面越少；局域网信号差时选「流畅」"));
        String[] subs = {L.t("1280 宽 · 帧率最高"), L.t("1920 宽 · 推荐"), L.t("原始分辨率 · 最清楚，帧率较低")};
        for (int i = 0; i < QUALITY_KEYS.length; i++) {
            String key = QUALITY_KEYS[i];
            boolean current = key.equals(quality);
            sheet.option(current ? R.drawable.ic_check : R.drawable.ic_hd, L.t(QUALITY_NAMES[i]) + (current ? L.t("（当前）") : ""), subs[i], false, () -> {
                quality = key;
                getSharedPreferences("remote", MODE_PRIVATE).edit().putString("quality", quality).apply();
                btnQuality.setText(qualityName());
                stopStream();
                startStream();
            });
        }
        sheet.dialog().setOnDismissListener(d -> immersive());
        sheet.show();
    }

    private void switchMonitor() {
        monitor = (Math.max(monitor, 0) + 1) % Math.max(1, monitorCount);
        stopStream();
        screen.resetZoom();
        startStream();
    }

    private void startStream() {
        if (running) return;
        running = true;
        showStatus(L.t("正在连接电脑画面…"));
        int id = ++streamId;
        streamThread = new Thread(() -> streamLoop(id), "remote-stream");
        streamThread.start();
    }

    private void stopStream() {
        running = false;
        HttpURLConnection c = streamConn;
        if (c != null) c.disconnect();
        if (streamThread != null) streamThread.interrupt();
    }

    private void streamLoop(int id) {
        Bitmap[] buffers = new Bitmap[2];
        int next = 0;
        try {
            String url = store.base() + "/api/remote/stream?q=" + quality + (monitor >= 0 ? "&monitor=" + monitor : "");
            HttpURLConnection c = Net.open(url, "GET", store.key());
            c.setReadTimeout(8000);
            streamConn = c;
            if (c.getResponseCode() != 200) {
                Net.readBody(c); // 抛出带服务器原因的异常
            }
            ui.post(() -> showStatus(null));
            DataInputStream in = new DataInputStream(new java.io.BufferedInputStream(c.getInputStream(), 256 * 1024));
            byte[] buf = new byte[512 * 1024];
            while (running && id == streamId) {
                int len = in.readInt();
                float cx = in.readFloat(), cy = in.readFloat();
                Bitmap frame = null;
                if (len > 0) {
                    if (len > buf.length) buf = new byte[len];
                    in.readFully(buf, 0, len);
                    // 两张位图轮流复用，避免每帧分配大内存
                    BitmapFactory.Options o = new BitmapFactory.Options();
                    o.inMutable = true;
                    o.inBitmap = buffers[next];
                    try {
                        frame = BitmapFactory.decodeByteArray(buf, 0, len, o);
                    } catch (IllegalArgumentException e) {
                        o.inBitmap = null; // 尺寸变了（切换屏幕），不能复用
                        frame = BitmapFactory.decodeByteArray(buf, 0, len, o);
                    }
                    buffers[next] = frame;
                    next ^= 1;
                }
                Bitmap f = frame;
                ui.post(() -> screen.update(f, cx, cy));
            }
        } catch (IOException e) {
            if (running && id == streamId) {
                String msg = e.getMessage() == null ? L.t("连接中断") : e.getMessage();
                ui.post(() -> showStatus(L.f("画面已断开\n%s\n\n点屏幕重新连接", msg)));
            }
        } finally {
            if (id == streamId) running = false;
        }
    }

    private void showStatus(String text) {
        status.setVisibility(text == null ? View.GONE : View.VISIBLE);
        if (text != null) status.setText(text);
    }

    // ---------- 发送操作 ----------

    private void send(JSONObject event) {
        synchronized (pending) {
            JSONObject last = pending.isEmpty() ? null : pending.get(pending.size() - 1);
            String type = event.optString("t");
            // 连续的绝对移动只保留最新一个；相对移动累加成一个，避免排队造成延迟
            if (last != null && type.equals(last.optString("t"))) {
                if ("move".equals(type)) {
                    pending.set(pending.size() - 1, event);
                    return;
                }
                if ("moveby".equals(type)) {
                    try {
                        last.put("dx", last.optDouble("dx") + event.optDouble("dx"));
                        last.put("dy", last.optDouble("dy") + event.optDouble("dy"));
                    } catch (JSONException ignored) {
                    }
                    return;
                }
            }
            pending.add(event);
            if (pending.size() > 1) return; // 已经安排过一次发送
        }
        sender.execute(this::flush);
    }

    private void flush() {
        JSONArray batch = new JSONArray();
        synchronized (pending) {
            for (JSONObject e : pending) batch.put(e);
            pending.clear();
        }
        if (batch.length() == 0) return;
        try {
            HttpURLConnection c = Net.open(store.base() + "/api/remote/input", "POST", store.key());
            byte[] body = batch.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
            c.setDoOutput(true);
            c.setFixedLengthStreamingMode(body.length);
            c.setRequestProperty("Content-Type", "application/json");
            try (java.io.OutputStream out = c.getOutputStream()) {
                out.write(body);
            }
            String res = Net.readBody(c);
            // 点到的是管理员权限的程序：Windows 会忽略这些操作，提示去电脑上授权
            if (res.contains("\"blocked\":true") && System.currentTimeMillis() - blockedHintAt > 8000) {
                blockedHintAt = System.currentTimeMillis();
                ui.post(() -> flash(L.t("这个窗口是管理员权限的程序，需要在电脑上「设置 → 远程控制」点「授权」后才能操作"), 5000));
            }
        } catch (IOException ignored) {
        }
    }

    private volatile long blockedHintAt;

    private static JSONObject ev(String type, Object... kv) {
        JSONObject o = new JSONObject();
        try {
            o.put("t", type);
            for (int i = 0; i + 1 < kv.length; i += 2) o.put((String) kv[i], kv[i + 1]);
        } catch (JSONException ignored) {
        }
        return o;
    }

    private void key(String k) {
        send(ev("key", "k", k));
    }

    // ---------- 键盘 ----------

    private void toggleKeyboard() {
        InputMethodManager imm = getSystemService(InputMethodManager.class);
        if (keyInput.hasFocus()) {
            keyInput.clearFocus();
            imm.hideSoftInputFromWindow(keyInput.getWindowToken(), 0);
            immersive();
        } else {
            keyInput.requestFocus();
            keyInput.setSelection(keyInput.length());
            imm.showSoftInput(keyInput, InputMethodManager.SHOW_IMPLICIT);
        }
    }

    private boolean resetting;
    private final TextWatcher keyWatcher = new TextWatcher() {
        @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
        @Override public void onTextChanged(CharSequence s, int a, int b, int c) {}

        @Override
        public void afterTextChanged(Editable s) {
            if (resetting) return;
            // 拼音还在组字时不发送，等输入法提交了汉字再发
            if (BaseInputConnection.getComposingSpanStart(s) != -1) return;
            String text = s.toString();
            if (text.equals(SENTINEL)) return;
            if (!text.startsWith(SENTINEL)) {
                key("backspace"); // 零宽字符被删掉了 = 用户按了退格
            } else {
                String typed = text.substring(SENTINEL.length());
                if (!typed.isEmpty()) send(ev("text", "s", typed));
            }
            resetting = true;
            s.replace(0, s.length(), SENTINEL);
            resetting = false;
        }
    };

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    // ---------- 画面与手势 ----------

    private class RemoteView extends View {
        private final Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);
        private final Paint cursorFill = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint cursorStroke = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path cursorPath = new Path();
        private final Matrix matrix = new Matrix();
        private final Matrix inverse = new Matrix();
        private Bitmap frame;
        private float cursorX = -1, cursorY = -1;
        private float zoom = 1f, panX, panY; // 用户缩放与平移（在适配屏幕的基础上）

        // 手势状态
        private static final int IDLE = 0, PENDING = 1, MOVING = 2, DRAG = 3, TWO = 4, DONE = 5, HOLD = 6;
        private int mode = IDLE;
        private float downX, downY, lastX, lastY, lastTapX = -1, lastTapY = -1;
        private long lastTapTime, lastMoveTime, twoStartTime, localCursorTime;
        private boolean dragCandidate, twoMoved, zooming;
        private float startDist, startZoom, startMidX, startMidY, startPanX, startPanY, scrollAccum;
        private final int slop = dp(8);
        private final Runnable longPress = () -> {
            if (mode != PENDING) return;
            performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS);
            if (touchpad) {
                send(ev("down", "b", "left")); // 触控板：长按后滑动 = 拖动
                mode = DRAG;
            } else {
                mode = HOLD; // 跟手：之后滑动 = 拖动，直接松开 = 右键
            }
        };

        RemoteView(Context c) {
            super(c);
            cursorFill.setColor(Color.WHITE);
            cursorStroke.setColor(Color.BLACK);
            cursorStroke.setStyle(Paint.Style.STROKE);
            cursorStroke.setStrokeWidth(dp(1));
            float s = dp(1);
            cursorPath.moveTo(0, 0);
            cursorPath.lineTo(0, 16 * s);
            cursorPath.lineTo(4.5f * s, 12 * s);
            cursorPath.lineTo(7.5f * s, 18 * s);
            cursorPath.lineTo(10 * s, 17 * s);
            cursorPath.lineTo(7 * s, 11 * s);
            cursorPath.lineTo(12 * s, 11 * s);
            cursorPath.close();
        }

        void update(Bitmap f, float cx, float cy) {
            if (f != null) frame = f;
            // 刚在本地移动过光标时，忽略画面里稍旧的光标位置，避免来回跳
            if (android.os.SystemClock.uptimeMillis() - localCursorTime > 300) {
                cursorX = cx;
                cursorY = cy;
            }
            followCursor();
            invalidate();
        }

        /** 放大时，光标快出可视区域就挪动画面跟上 */
        private void followCursor() {
            if (zoom <= 1.01f || frame == null || cursorX < 0 || cursorY < 0) return;
            computeMatrix();
            float[] pt = {cursorX * frame.getWidth(), cursorY * frame.getHeight()};
            matrix.mapPoints(pt);
            float margin = dp(48);
            if (pt[0] < margin) panX += margin - pt[0];
            else if (pt[0] > getWidth() - margin) panX -= pt[0] - (getWidth() - margin);
            if (pt[1] < margin) panY += margin - pt[1];
            else if (pt[1] > getHeight() - margin) panY -= pt[1] - (getHeight() - margin);
        }

        void resetZoom() {
            zoom = 1f;
            panX = panY = 0;
            invalidate();
        }

        private void computeMatrix() {
            matrix.reset();
            if (frame == null) return;
            float fit = Math.min(getWidth() / (float) frame.getWidth(), getHeight() / (float) frame.getHeight());
            float s = fit * zoom;
            float w = frame.getWidth() * s, h = frame.getHeight() * s;
            // 放大后不允许拖出边界
            float maxPanX = Math.max(0, (w - getWidth()) / 2), maxPanY = Math.max(0, (h - getHeight()) / 2);
            panX = Math.max(-maxPanX, Math.min(maxPanX, panX));
            panY = Math.max(-maxPanY, Math.min(maxPanY, panY));
            matrix.postScale(s, s);
            matrix.postTranslate((getWidth() - w) / 2 + panX, (getHeight() - h) / 2 + panY);
            matrix.invert(inverse);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            if (frame == null || frame.isRecycled()) return;
            computeMatrix();
            canvas.drawBitmap(frame, matrix, paint);
            if (cursorX >= 0 && cursorX <= 1 && cursorY >= 0 && cursorY <= 1) {
                float[] pt = {cursorX * frame.getWidth(), cursorY * frame.getHeight()};
                matrix.mapPoints(pt);
                canvas.save();
                canvas.translate(pt[0], pt[1]);
                canvas.drawPath(cursorPath, cursorFill);
                canvas.drawPath(cursorPath, cursorStroke);
                canvas.restore();
            }
        }

        /** 屏幕上的点 → 电脑画面上的相对坐标（0~1），落在画面外返回 null */
        private float[] toRemote(float x, float y) {
            if (frame == null) return null;
            computeMatrix();
            float[] pt = {x, y};
            inverse.mapPoints(pt);
            float rx = pt[0] / frame.getWidth(), ry = pt[1] / frame.getHeight();
            if (rx < 0 || rx > 1 || ry < 0 || ry > 1) return null;
            return new float[]{rx, ry};
        }

        @Override
        public boolean onTouchEvent(MotionEvent e) {
            if (!running && e.getActionMasked() == MotionEvent.ACTION_UP) {
                startStream(); // 断开状态下点屏幕重连
                return true;
            }
            float x = e.getX(), y = e.getY();
            long t = e.getEventTime();
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    mode = PENDING;
                    downX = lastX = x;
                    downY = lastY = y;
                    lastMoveTime = t;
                    // 刚轻点过又按下：触控板里「点两下按住滑动」= 拖动
                    dragCandidate = touchpad && t - lastTapTime < 300 && Math.hypot(x - lastTapX, y - lastTapY) < dp(60);
                    if (!touchpad) pointAt(x, y);
                    postDelayed(longPress, 450);
                    break;
                case MotionEvent.ACTION_POINTER_DOWN:
                    removeCallbacks(longPress);
                    if (mode == DRAG) send(ev("up", "b", "left"));
                    if (e.getPointerCount() == 2) {
                        mode = TWO;
                        twoMoved = false;
                        zooming = false;
                        twoStartTime = t;
                        scrollAccum = 0;
                        startDist = dist(e);
                        startZoom = zoom;
                        startMidX = midX(e);
                        startMidY = midY(e);
                        startPanX = panX;
                        startPanY = panY;
                    }
                    break;
                case MotionEvent.ACTION_MOVE:
                    if ((mode == PENDING || mode == HOLD) && Math.hypot(x - downX, y - downY) > slop) {
                        removeCallbacks(longPress);
                        if (touchpad) {
                            if (dragCandidate) send(ev("down", "b", "left"));
                            mode = dragCandidate ? DRAG : MOVING;
                        } else if (mode == HOLD) {
                            float[] p = toRemote(downX, downY);
                            if (p != null) {
                                send(ev("move", "x", p[0], "y", p[1]));
                                send(ev("down", "b", "left"));
                            }
                            mode = DRAG;
                        } else {
                            mode = MOVING;
                        }
                    }
                    if (mode == MOVING || mode == DRAG) {
                        if (touchpad) {
                            relativeMove(x, y, t);
                        } else {
                            pointAt(x, y);
                        }
                    } else if (mode == TWO && e.getPointerCount() >= 2) {
                        twoFinger(e);
                    }
                    break;
                case MotionEvent.ACTION_POINTER_UP:
                    if (mode == TWO) {
                        // 双指轻点（没移动、时间短）= 右键
                        if (!twoMoved && t - twoStartTime < 300) {
                            if (touchpad) {
                                send(ev("click", "b", "right"));
                            } else {
                                float[] p = toRemote(startMidX, startMidY);
                                if (p != null) send(ev("click", "b", "right", "x", p[0], "y", p[1]));
                            }
                        }
                        if (zooming && zoom < 1.05f) resetZoom();
                        mode = DONE;
                    }
                    break;
                case MotionEvent.ACTION_UP:
                    removeCallbacks(longPress);
                    if (mode == PENDING) {
                        if (touchpad) {
                            send(ev("click", "b", "left"));
                            lastTapTime = t;
                            lastTapX = x;
                            lastTapY = y;
                        } else {
                            tapAt(x, y, t);
                        }
                    } else if (mode == HOLD) {
                        float[] p = toRemote(downX, downY);
                        if (p != null) send(ev("click", "b", "right", "x", p[0], "y", p[1]));
                    } else if (mode == DRAG) {
                        send(ev("up", "b", "left"));
                    }
                    mode = IDLE;
                    break;
                case MotionEvent.ACTION_CANCEL:
                    removeCallbacks(longPress);
                    if (mode == DRAG) send(ev("up", "b", "left"));
                    mode = IDLE;
                    break;
            }
            return true;
        }

        /** 触控板：手指位移 → 鼠标相对位移，滑得越快走得越远 */
        private void relativeMove(float x, float y, long t) {
            float dx = x - lastX, dy = y - lastY;
            long dt = Math.max(1, t - lastMoveTime);
            lastX = x;
            lastY = y;
            lastMoveTime = t;
            if (frame == null || (dx == 0 && dy == 0)) return;
            float density = getResources().getDisplayMetrics().density;
            float speed = (float) Math.hypot(dx, dy) / dt / density; // dp/毫秒
            float gain = 1f + Math.min(2.5f, speed * 1.5f);
            // 基准：手指划过整个手机宽度 ≈ 鼠标走 1.2 个屏幕宽；放大时更精细
            float base = 1.2f / Math.max(1, getWidth()) * gain / zoom;
            float aspect = frame.getWidth() / (float) frame.getHeight();
            float ndx = dx * base, ndy = dy * base * aspect;
            send(ev("moveby", "dx", ndx, "dy", ndy));
            // 本地先画出新位置，不用等画面回传
            if (cursorX >= 0 && cursorY >= 0) {
                cursorX = Math.max(0, Math.min(1, cursorX + ndx));
                cursorY = Math.max(0, Math.min(1, cursorY + ndy));
                localCursorTime = android.os.SystemClock.uptimeMillis();
                followCursor();
                invalidate();
            }
        }

        /** 跟手模式：鼠标移到手指所在位置，本地先画出来 */
        private void pointAt(float x, float y) {
            float[] p = toRemote(x, y);
            if (p == null) return;
            send(ev("move", "x", p[0], "y", p[1]));
            cursorX = p[0];
            cursorY = p[1];
            localCursorTime = android.os.SystemClock.uptimeMillis();
            invalidate();
        }

        private void tapAt(float x, float y, long t) {
            // 快速连点时对齐到上一次的位置，电脑才会认成双击
            if (t - lastTapTime < 350 && Math.hypot(x - lastTapX, y - lastTapY) < dp(30)) {
                x = lastTapX;
                y = lastTapY;
            }
            lastTapTime = t;
            lastTapX = x;
            lastTapY = y;
            float[] p = toRemote(x, y);
            if (p != null) send(ev("click", "b", "left", "x", p[0], "y", p[1]));
        }

        private float fitScale() {
            return Math.min(getWidth() / (float) frame.getWidth(), getHeight() / (float) frame.getHeight());
        }

        private void twoFinger(MotionEvent e) {
            float d = dist(e), mx = midX(e), my = midY(e);
            float ratio = d / Math.max(1, startDist);
            if (Math.abs(d - startDist) > slop || Math.hypot(mx - startMidX, my - startMidY) > slop) twoMoved = true;
            // 两指距离明显变化 = 缩放；已经放大时，双指移动 = 平移画面
            if (!zooming && (Math.abs(ratio - 1) > 0.10f || (startZoom > 1.01f && twoMoved))) zooming = true;
            if (zooming) {
                if (frame == null) return;
                float fit = fitScale();
                float fw = frame.getWidth(), fh = frame.getHeight();
                // 手势开始时两指中点下的画面位置，缩放和移动后仍然保持在两指中点下（和看图软件一样）
                float ix = (startMidX - ((getWidth() - fw * fit * startZoom) / 2 + startPanX)) / (fit * startZoom);
                float iy = (startMidY - ((getHeight() - fh * fit * startZoom) / 2 + startPanY)) / (fit * startZoom);
                zoom = Math.max(1f, Math.min(5f, startZoom * ratio));
                panX = mx - ix * fit * zoom - (getWidth() - fw * fit * zoom) / 2;
                panY = my - iy * fit * zoom - (getHeight() - fh * fit * zoom) / 2;
                if (Math.abs(ratio - 1) > 0.02f) flash(zoom < 1.05f ? L.t("适应屏幕") : L.f("放大 %d%%", Math.round(zoom * 100)), 1200);
                invalidate();
            } else {
                // 未放大时双指上下滑动 = 滚轮（手指下移 = 往上滚，和手机里滑动页面的方向一致）
                float dy = my - startMidY;
                float steps = (dy - scrollAccum) / dp(24);
                if (Math.abs(steps) >= 1) {
                    int n = (int) steps;
                    scrollAccum += n * dp(24);
                    send(ev("scroll", "dy", n * 120));
                }
            }
        }

        private float dist(MotionEvent e) {
            return (float) Math.hypot(e.getX(0) - e.getX(1), e.getY(0) - e.getY(1));
        }

        private float midX(MotionEvent e) {
            return (e.getX(0) + e.getX(1)) / 2;
        }

        private float midY(MotionEvent e) {
            return (e.getY(0) + e.getY(1)) / 2;
        }
    }
}
