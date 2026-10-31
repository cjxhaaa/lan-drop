package com.cjxh.filetransfer;

import android.app.Activity;
import android.app.Dialog;
import android.app.DownloadManager;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.media.ThumbnailUtils;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.InputType;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.text.util.Linkify;
import android.util.LruCache;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/** 主界面：和电脑的聊天记录（文字、图片、文件按时间排列），可切换到「文件」查看电脑共享文件夹。 */
public class MainActivity extends Activity {
    private static final int REQ_PICK = 1;
    private static final int REQ_PROJECT = 2;
    private boolean mirrorAfterSettings; // 从无障碍设置回来后继续投屏
    private static final long POLL_MS = 2000;
    private static final String IMAGE_EXT = "jpg|jpeg|png|gif|webp|bmp|avif";

    private final ExecutorService io = Executors.newCachedThreadPool();
    private final ExecutorService thumbIo = Executors.newFixedThreadPool(2);
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final AtomicBoolean refreshing = new AtomicBoolean();
    private final Set<String> downloaded = new HashSet<>();
    private final LruCache<String, Bitmap> thumbs = new LruCache<String, Bitmap>(24 * 1024 * 1024) {
        @Override
        protected int sizeOf(String key, Bitmap value) {
            return value.getByteCount();
        }
    };
    private Store store;
    private boolean connected, resumed, filesMode;
    private String lastFiles = "";
    private String me;

    // 聊天状态
    private long lastSeq;
    private JSONObject lastMsg;
    private boolean chatEmpty = true;

    private TextView pcName, status, tabChat, tabFiles, filesCount, btnSend;
    private View statusDot, composer;
    private ScrollView chatScroll, filesScroll;
    private LinearLayout chatList, filesBox;
    private EditText input;

    private final Runnable poll = new Runnable() {
        @Override
        public void run() {
            refresh(!connected);
            ui.postDelayed(this, POLL_MS);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        store = new Store(this);
        me = Net.deviceName();

        pcName = findViewById(R.id.pc_name);
        status = findViewById(R.id.status);
        statusDot = findViewById(R.id.status_dot);
        tabChat = findViewById(R.id.tab_chat);
        tabFiles = findViewById(R.id.tab_files);
        filesCount = findViewById(R.id.files_count);
        composer = findViewById(R.id.composer);
        chatScroll = findViewById(R.id.chat_scroll);
        filesScroll = findViewById(R.id.files_scroll);
        chatList = findViewById(R.id.chat_list);
        filesBox = findViewById(R.id.files);
        input = findViewById(R.id.input);
        btnSend = findViewById(R.id.btn_send);
        edgeToEdge(findViewById(R.id.root), () -> scrollChatToBottom(false));

        findViewById(R.id.conn).setOnClickListener(v -> connectMenu());
        tabChat.setOnClickListener(v -> setMode(false));
        tabFiles.setOnClickListener(v -> setMode(true));
        findViewById(R.id.btn_attach).setOnClickListener(v -> pickFiles());
        findViewById(R.id.btn_remote).setOnClickListener(v -> {
            if (!store.paired()) findPc();
            else if (!connected) toast("电脑不在线，连上后才能控制");
            else startActivity(new Intent(this, RemoteActivity.class));
        });
        btnSend.setOnClickListener(v -> sendText());
        input.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void afterTextChanged(Editable s) {
                btnSend.setVisibility(s.toString().trim().isEmpty() ? View.GONE : View.VISIBLE);
            }
        });
        renderStatus();
        resetChat();
    }

    @Override
    protected void onResume() {
        super.onResume();
        resumed = true;
        NotifyService.appVisible = true;
        startNotifications();
        if (mirrorAfterSettings) {
            mirrorAfterSettings = false;
            ui.postDelayed(this::requestProjection, 400);
        }
        connected = false;
        resetChat(); // 每次回到前台完整拉一次，避免电脑端记录变化后对不上
        ui.removeCallbacks(poll);
        ui.post(poll);
        if (!store.paired()) ui.postDelayed(this::findPc, 300);
    }

    @Override
    protected void onPause() {
        super.onPause();
        resumed = false;
        NotifyService.appVisible = false;
        ui.removeCallbacks(poll);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        io.shutdownNow();
        thumbIo.shutdownNow();
    }

    /** targetSdk 35+ 默认全面屏绘制：给状态栏、导航栏、输入法让出空间。 */
    static void edgeToEdge(View root, Runnable onImeChange) {
        Activity a = (Activity) root.getContext();
        if (Build.VERSION.SDK_INT >= 30) {
            a.getWindow().setDecorFitsSystemWindows(false);
        } else {
            root.setSystemUiVisibility(View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
        }
        int[] lastBottom = {0};
        root.setOnApplyWindowInsetsListener((v, insets) -> {
            int l, t, r, b;
            if (Build.VERSION.SDK_INT >= 30) {
                android.graphics.Insets bars = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
                android.graphics.Insets ime = insets.getInsets(WindowInsets.Type.ime());
                l = bars.left; t = bars.top; r = bars.right; b = Math.max(bars.bottom, ime.bottom);
            } else {
                l = insets.getSystemWindowInsetLeft(); t = insets.getSystemWindowInsetTop();
                r = insets.getSystemWindowInsetRight(); b = insets.getSystemWindowInsetBottom();
            }
            v.setPadding(l, t, r, b);
            if (b != lastBottom[0] && onImeChange != null) v.post(onImeChange);
            lastBottom[0] = b;
            return insets;
        });
    }

    // ---------- 模式切换 ----------

    private void setMode(boolean files) {
        filesMode = files;
        chatScroll.setVisibility(files ? View.GONE : View.VISIBLE);
        filesScroll.setVisibility(files ? View.VISIBLE : View.GONE);
        composer.setVisibility(files ? View.GONE : View.VISIBLE);
        styleTab(tabChat, !files);
        styleTab(tabFiles, files);
        if (files) {
            hideKeyboard();
            lastFiles = "";
            refresh(false);
        } else {
            scrollChatToBottom(false);
        }
    }

    private void styleTab(TextView tab, boolean on) {
        tab.setBackgroundResource(on ? R.drawable.seg_on : 0);
        tab.setTextColor(getColor(on ? R.color.text : R.color.muted));
        tab.setTypeface(on ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
    }

    private void hideKeyboard() {
        input.clearFocus();
        getSystemService(android.view.inputmethod.InputMethodManager.class)
                .hideSoftInputFromWindow(input.getWindowToken(), 0);
    }

    // ---------- 连接 ----------

    private void renderStatus() {
        int dotColor;
        if (!store.paired()) {
            pcName.setText("未连接电脑");
            status.setText("点这里查找电脑");
            dotColor = R.color.faint;
        } else if (connected) {
            pcName.setText(store.name());
            status.setText("已连接");
            dotColor = R.color.ok;
        } else {
            pcName.setText(store.name());
            status.setText("电脑不在线");
            dotColor = R.color.danger;
        }
        statusDot.setBackgroundTintList(ColorStateList.valueOf(getColor(dotColor)));
    }

    private void connectMenu() {
        if (!store.paired()) {
            findPc();
            return;
        }
        Sheet sheet = new Sheet(this)
                .icon(R.drawable.ic_computer, R.color.accent, R.drawable.circle_soft)
                .title(store.name())
                .message(connected ? "已连接 · " + store.ip() + ":" + store.port() : "不在线 · 请确认电脑上打开了「文件互传」，并且在同一个 Wi-Fi");
        if (!connected) {
            sheet.option(R.drawable.ic_refresh, "重新连接", null, false, () -> {
                toast("正在重新连接…");
                refresh(true);
            });
        }
        sheet.toggle(R.drawable.ic_bell, "后台消息通知", "App 没打开时，电脑发来消息也会提醒你", NotifyService.enabled(this), on -> {
                    NotifyService.setEnabled(this, on);
                    if (on) startNotifications();
                })
                .option(R.drawable.ic_cast, MirrorService.running ? "停止投屏" : "投屏到电脑",
                        MirrorService.running ? "电脑上正在显示这台手机的屏幕" : "在电脑上查看和操作这台手机", MirrorService.running, () -> {
                            if (MirrorService.running) startService(new Intent(this, MirrorService.class).setAction(MirrorService.ACTION_STOP));
                            else startMirror();
                        })
                .option(R.drawable.ic_swap, "连接其他电脑", null, false, this::findPc)
                .option(R.drawable.ic_logout, "断开并忘记这台电脑", "之后需要重新配对", true, this::confirmForget)
                .show();
    }

    private void confirmForget() {
        new Sheet(this)
                .icon(R.drawable.ic_logout, R.color.danger, R.drawable.circle_soft)
                .title("忘记「" + store.name() + "」？")
                .message("断开后不再自动连接这台电脑，下次需要在电脑上重新允许。")
                .buttons("取消", null, "断开", () -> {
                    stopService(new Intent(this, NotifyService.class));
                    store.clear();
                    connected = false;
                    renderStatus();
                    resetChat();
                    lastFiles = "";
                    renderFilesEmpty("还没有连接电脑");
                }, true)
                .show();
    }

    // ---------- 连接电脑的弹窗 ----------

    private Dialog picker;
    private LinearLayout pickerList;

    /** 打开「连接电脑」弹窗并开始查找；弹窗已打开时只刷新列表 */
    private void findPc() {
        if (!resumed) return;
        if (picker == null || !picker.isShowing()) picker = buildPicker();
        else picker.show();
        pickerList.removeAllViews();
        pickerList.addView(searchingRow());
        io.execute(() -> {
            List<Net.Pc> list = Net.discover(2000);
            ui.post(() -> {
                if (picker == null || !picker.isShowing() || isFinishing()) return;
                pickerList.removeAllViews();
                if (list.isEmpty()) {
                    pickerList.addView(notFoundView());
                } else {
                    for (Net.Pc pc : list) pickerList.addView(pcRow(pc));
                }
            });
        });
    }

    private Dialog buildPicker() {
        pickerList = new LinearLayout(this);
        pickerList.setOrientation(LinearLayout.VERTICAL);
        return new Sheet(this)
                .icon(R.drawable.ic_wifi, R.color.accent, R.drawable.circle_soft)
                .title("连接电脑")
                .message("手机和电脑连在同一个 Wi-Fi，电脑上打开「文件互传」")
                .view(pickerList)
                .buttons("输入 IP", this::manualIp, "重新查找", this::findPc, false)
                .show();
    }

    private View searchingRow() {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(4), dp(18), dp(4), dp(18));
        android.widget.ProgressBar spin = new android.widget.ProgressBar(this);
        spin.setIndeterminateTintList(ColorStateList.valueOf(getColor(R.color.accent)));
        row.addView(spin, new LinearLayout.LayoutParams(dp(22), dp(22)));
        TextView t = label("正在查找附近的电脑…", 15, R.color.muted);
        t.setPadding(dp(12), 0, 0, 0);
        row.addView(t);
        return row;
    }

    /** 一台电脑一张卡片：图标 + 名字 + IP + 「连接」按钮，整张都能点 */
    private View pcRow(Net.Pc pc) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setBackgroundResource(R.drawable.bubble_bg);
        row.setPadding(dp(14), dp(12), dp(12), dp(12));

        FrameLayout ic = new FrameLayout(this);
        ic.setBackgroundResource(R.drawable.circle_soft);
        ImageView img = new ImageView(this);
        img.setImageResource(R.drawable.ic_computer);
        img.setImageTintList(ColorStateList.valueOf(getColor(R.color.accent)));
        ic.addView(img, new FrameLayout.LayoutParams(dp(22), dp(22), Gravity.CENTER));
        row.addView(ic, new LinearLayout.LayoutParams(dp(44), dp(44)));

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(dp(12), 0, dp(8), 0);
        TextView name = label(pc.name, 16, R.color.text);
        name.setTypeface(Typeface.DEFAULT_BOLD);
        name.setSingleLine(true);
        name.setEllipsize(TextUtils.TruncateAt.END);
        col.addView(name);
        boolean current = pc.id.equals(store.id());
        col.addView(label(pc.ip, 12, R.color.muted));
        row.addView(col, new LinearLayout.LayoutParams(0, -2, 1));

        // 已经连着的这台只显示「当前」，点了直接关闭，不重复配对
        TextView go = pill(current && connected ? "当前" : "连接", !(current && connected));
        go.setPadding(dp(16), 0, dp(16), 0);
        row.addView(go, new LinearLayout.LayoutParams(-2, dp(36)));

        View.OnClickListener connect = v -> {
            if (picker != null) picker.dismiss();
            if (!(current && connected)) pairWith(pc);
        };
        row.setOnClickListener(connect);
        go.setOnClickListener(connect);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.bottomMargin = dp(8);
        row.setLayoutParams(lp);
        return row;
    }

    private View notFoundView() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setBackgroundResource(R.drawable.bubble_bg);
        box.setPadding(dp(16), dp(14), dp(16), dp(14));
        TextView t = label("没有找到电脑", 16, R.color.text);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        box.addView(t);
        TextView tips = label("· 电脑上已打开「文件互传」\n· 手机和电脑连的是同一个 Wi-Fi / 路由器\n· 电脑第一次运行时，防火墙弹窗里允许了「专用网络」\n还是不行的话，点「输入 IP」，地址在电脑端「设置 → 本机地址」",
                13, R.color.muted);
        tips.setLineSpacing(dp(4), 1);
        tips.setPadding(0, dp(6), 0, 0);
        box.addView(tips);
        return box;
    }

    private TextView pill(String text, boolean primary) {
        TextView b = new TextView(this);
        b.setText(text);
        b.setGravity(Gravity.CENTER);
        b.setTextSize(15);
        b.setTypeface(Typeface.DEFAULT_BOLD);
        b.setTextColor(primary ? Color.WHITE : getColor(R.color.accent));
        b.setBackgroundResource(primary ? R.drawable.pill_primary : R.drawable.pill_soft);
        return b;
    }

    private void manualIp() {
        EditText field = new EditText(this);
        field.setHint("例如 192.168.1.5");
        field.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        field.setText(store.ip());
        field.setBackgroundResource(R.drawable.input_bg);
        field.setPadding(dp(16), dp(12), dp(16), dp(12));
        field.setTextColor(getColor(R.color.text));
        field.setHintTextColor(getColor(R.color.faint));
        field.setTextSize(16);
        field.setSingleLine(true);
        new Sheet(this)
                .icon(R.drawable.ic_wifi, R.color.accent, R.drawable.circle_soft)
                .title("输入电脑地址")
                .message("在电脑端「设置 → 本机地址」里可以看到")
                .view(field)
                .buttons("取消", null, "连接", () -> {
                    String text = field.getText().toString().trim().replace("http://", "");
                    String ip = text;
                    int port = 8765;
                    int colon = text.indexOf(':');
                    if (colon > 0) {
                        ip = text.substring(0, colon);
                        try {
                            port = Integer.parseInt(text.substring(colon + 1).replaceAll("/.*", ""));
                        } catch (NumberFormatException ignored) {
                        }
                    }
                    String fIp = ip;
                    int fPort = port;
                    io.execute(() -> {
                        Net.Pc pc = Net.hello(fIp, fPort, "");
                        ui.post(() -> {
                            if (pc == null) showError("连接不上 " + fIp + ":" + fPort, "请确认地址正确、电脑上打开了「文件互传」，并且手机和电脑在同一个 Wi-Fi。");
                            else pairWith(pc);
                        });
                    });
                }, false)
                .show();
        field.requestFocus();
    }

    private void pairWith(Net.Pc pc) {
        android.widget.ProgressBar spin = new android.widget.ProgressBar(this);
        spin.setIndeterminateTintList(ColorStateList.valueOf(getColor(R.color.accent)));
        LinearLayout spinBox = new LinearLayout(this);
        spinBox.setGravity(Gravity.CENTER);
        spinBox.addView(spin, new LinearLayout.LayoutParams(dp(32), dp(32)));
        Dialog waiting = new Sheet(this)
                .icon(R.drawable.ic_computer, R.color.accent, R.drawable.circle_soft)
                .title("等待电脑确认")
                .message("请在电脑「" + pc.name + "」弹出的窗口里点「允许连接」。")
                .view(spinBox)
                .buttons("取消", null, null, null, false)
                .cancelable(false)
                .show();
        io.execute(() -> {
            try {
                String key = Net.pair(pc);
                store.save(pc, key);
                ui.post(() -> {
                    waiting.dismiss();
                    toast("已连接「" + pc.name + "」");
                    startNotifications();
                    connected = true;
                    lastFiles = "";
                    resetChat();
                    renderStatus();
                    refresh(false);
                });
            } catch (IOException e) {
                ui.post(() -> {
                    if (!waiting.isShowing()) return;
                    waiting.dismiss();
                    showError("连接失败", e.getMessage());
                });
            }
        });
    }

    private void showError(String title, String message) {
        new Sheet(this)
                .icon(R.drawable.ic_close, R.color.danger, R.drawable.circle_soft)
                .title(title)
                .message(message == null ? "" : message)
                .buttons(null, null, "好", null, false)
                .show();
    }

    /** 投屏：先确认是否开启了「电脑操作手机」，再申请系统录屏权限 */
    private void startMirror() {
        if (!connected) {
            toast("电脑不在线，连上后才能投屏");
            return;
        }
        if (ControlService.enabled()) {
            requestProjection();
            return;
        }
        new Sheet(this)
                .icon(R.drawable.ic_cast, R.color.accent, R.drawable.circle_soft)
                .title("让电脑也能操作手机？")
                .message("投屏后电脑上能看到手机画面。想在电脑上用鼠标点击、滑动、打字，需要在系统「无障碍」里打开「文件互传 · 电脑操作手机」。\n\n小米、华为等手机如果提示「受限设置」，先到 设置 → 应用 → 文件互传 → 右上角菜单 → 允许受限制的设置。")
                .buttons("只投屏", this::requestProjection, "去开启", () -> {
                    mirrorAfterSettings = true;
                    try {
                        startActivity(new Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS));
                        toast("找到「文件互传 · 电脑操作手机」并打开");
                    } catch (RuntimeException e) {
                        mirrorAfterSettings = false;
                        requestProjection();
                    }
                }, false)
                .show();
    }

    private void requestProjection() {
        android.media.projection.MediaProjectionManager mpm = getSystemService(android.media.projection.MediaProjectionManager.class);
        startActivityForResult(mpm.createScreenCaptureIntent(), REQ_PROJECT);
    }

    /** 启动后台通知服务；安卓 13 以上先申请通知权限 */
    private void startNotifications() {
        if (!store.paired() || !NotifyService.enabled(this)) return;
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            if (!getSharedPreferences("pc", MODE_PRIVATE).getBoolean("asked_notify", false)) {
                getSharedPreferences("pc", MODE_PRIVATE).edit().putBoolean("asked_notify", true).apply();
                requestPermissions(new String[]{android.Manifest.permission.POST_NOTIFICATIONS}, 7);
            }
        }
        NotifyService.start(this);
    }

    // ---------- 拉取 ----------

    private void refresh(boolean checkConnection) {
        if (!store.paired() || !refreshing.compareAndSet(false, true)) return;
        boolean wantFiles = filesMode;
        long after = lastSeq;
        io.execute(() -> {
            boolean ok = !checkConnection || Net.ensure(store);
            JSONArray msgs = null, files = null;
            if (ok) {
                try {
                    msgs = Net.getArray(store, "/api/messages?after=" + after);
                    if (wantFiles) files = Net.getArray(store, "/api/files");
                } catch (IOException e) {
                    ok = false;
                }
            }
            boolean fOk = ok;
            JSONArray fMsgs = msgs, fFiles = files;
            ui.post(() -> {
                refreshing.set(false);
                boolean changed = connected != fOk;
                connected = fOk;
                renderStatus();
                if (fOk) {
                    if (after == lastSeq) appendMessages(fMsgs); // 期间被重置过就丢弃这批
                    if (fFiles != null) renderFiles(fFiles);
                } else if (changed && filesMode) {
                    lastFiles = "";
                    renderFilesEmpty("电脑不在线，打开电脑端后会自动连接");
                }
            });
        });
    }

    // ---------- 聊天 ----------

    private void resetChat() {
        lastSeq = 0;
        lastMsg = null;
        chatEmpty = true;
        chatList.removeAllViews();
        chatList.addView(chatPlaceholder(store.paired() ? "正在加载聊天记录…" : "连接电脑后，在这里收发文字、图片和文件"));
    }

    private View chatPlaceholder(String text) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER);
        box.setPadding(dp(32), dp(120), dp(32), dp(32));
        FrameLayout circle = new FrameLayout(this);
        circle.setBackgroundResource(R.drawable.circle_soft);
        ImageView icon = new ImageView(this);
        icon.setImageResource(R.drawable.ic_text);
        icon.setImageTintList(ColorStateList.valueOf(getColor(R.color.accent)));
        circle.addView(icon, new FrameLayout.LayoutParams(dp(28), dp(28), Gravity.CENTER));
        box.addView(circle, new LinearLayout.LayoutParams(dp(64), dp(64)));
        TextView t = label(text, 14, R.color.muted);
        t.setGravity(Gravity.CENTER);
        t.setLineSpacing(dp(3), 1);
        t.setPadding(0, dp(14), 0, 0);
        box.addView(t);
        box.setTag("placeholder");
        return box;
    }

    private void appendMessages(JSONArray msgs) {
        boolean stick = isChatAtBottom();
        boolean mineAdded = false;
        for (int i = 0; i < msgs.length(); i++) {
            JSONObject m = msgs.optJSONObject(i);
            long seq = m.optLong("seq");
            if (seq <= lastSeq) continue;
            lastSeq = seq;
            if ("deleted".equals(m.optString("kind"))) continue; // 电脑上删掉的记录
            if ("cleared".equals(m.optString("kind"))) {
                lastMsg = null;
                chatEmpty = true;
                chatList.removeAllViews();
                continue;
            }
            if (chatEmpty) {
                chatList.removeAllViews();
                chatEmpty = false;
            }
            addMessage(m);
            if (isMine(m)) mineAdded = true;
        }
        if (chatEmpty && (chatList.getChildCount() == 0 || "placeholder".equals(chatList.getChildAt(0).getTag()))) {
            chatList.removeAllViews();
            chatList.addView(chatPlaceholder("还没有消息\n发文字、图片或文件，电脑上会立刻收到"));
        }
        if (stick || mineAdded) scrollChatToBottom(true);
    }

    private boolean isMine(JSONObject m) {
        return "phone".equals(m.optString("side")) && me.equals(m.optString("from"));
    }

    private void addMessage(JSONObject m) {
        double time = m.optDouble("time");
        boolean mine = isMine(m);
        boolean newSection = lastMsg == null || time - lastMsg.optDouble("time") > 300;
        boolean grouped = !newSection && lastMsg.optString("side").equals(m.optString("side"))
                && lastMsg.optString("from").equals(m.optString("from")) && time - lastMsg.optDouble("time") < 120;
        lastMsg = m;

        if (newSection) {
            TextView sep = label(fmtChatTime(time), 12, R.color.faint);
            sep.setGravity(Gravity.CENTER);
            sep.setPadding(0, dp(14), 0, dp(4));
            chatList.addView(sep, new LinearLayout.LayoutParams(-1, -2));
        }

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setGravity(mine ? Gravity.END : Gravity.START);
        if (!mine && !grouped) {
            String from = "pc".equals(m.optString("side")) ? store.name() : m.optString("from");
            TextView sender = label(from, 12, R.color.faint);
            sender.setPadding(dp(6), 0, dp(6), dp(3));
            col.addView(sender);
        }
        col.addView(bubble(m, mine));

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = dp(grouped ? 4 : 10);
        col.setLayoutParams(lp);
        chatList.addView(col);
    }

    private View bubble(JSONObject m, boolean mine) {
        int maxW = (int) (getResources().getDisplayMetrics().widthPixels * 0.74);
        if ("text".equals(m.optString("kind"))) {
            String text = m.optString("text");
            TextView t = label(text, 15, mine ? R.color.card : R.color.text);
            if (mine) t.setTextColor(Color.WHITE);
            t.setBackgroundResource(mine ? R.drawable.bubble_mine : R.drawable.bubble_theirs);
            t.setPadding(dp(14), dp(10), dp(14), dp(10));
            t.setMaxWidth(maxW);
            t.setLineSpacing(dp(2), 1);
            t.setAutoLinkMask(Linkify.WEB_URLS);
            t.setLinkTextColor(mine ? Color.WHITE : getColor(R.color.accent));
            t.setText(text);
            t.setOnLongClickListener(v -> {
                copy(text);
                return true;
            });
            // 必须是 wrap_content，否则气泡会撑满整行，也无法靠右
            t.setLayoutParams(new LinearLayout.LayoutParams(-2, -2));
            return t;
        }
        String name = m.optString("name");
        boolean exists = m.optBoolean("exists", true);
        if (isImage(name) && exists) return imageBubble(name);
        return fileCard(name, m.optLong("size"), exists);
    }

    private View imageBubble(String name) {
        ImageView iv = new ImageView(this);
        iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
        iv.setBackgroundResource(R.drawable.image_bg);
        iv.setClipToOutline(true);
        iv.setLayoutParams(new LinearLayout.LayoutParams(dp(140), dp(140)));
        int box = dp(160);
        loadBitmap(name, box, "chat", bm -> {
            // 按原图比例放进 160dp 的方框
            float ratio = bm.getWidth() / (float) Math.max(1, bm.getHeight());
            int w = ratio >= 1 ? box : Math.max(dp(80), Math.round(box * ratio));
            int h = ratio >= 1 ? Math.max(dp(80), Math.round(box / ratio)) : box;
            boolean stick = isChatAtBottom();
            iv.setLayoutParams(new LinearLayout.LayoutParams(w, h));
            iv.setImageBitmap(bm);
            if (stick) scrollChatToBottom(false);
        });
        iv.setOnClickListener(v -> showImage(name));
        iv.setOnLongClickListener(v -> {
            download(name);
            return true;
        });
        return iv;
    }

    private View fileCard(String name, long size, boolean exists) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.HORIZONTAL);
        card.setGravity(Gravity.CENTER_VERTICAL);
        card.setBackgroundResource(R.drawable.bubble_theirs);
        card.setPadding(dp(14), dp(12), dp(12), dp(12));
        card.setLayoutParams(new LinearLayout.LayoutParams(dp(250), -2));

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        TextView title = label(name, 15, R.color.text);
        title.setMaxLines(2);
        title.setEllipsize(TextUtils.TruncateAt.MIDDLE);
        col.addView(title);
        String state = !exists ? "已删除或移走" : downloaded.contains(name) ? "已下载" : "点击下载";
        TextView sub = label(fmtSize(size) + " · " + state, 12, R.color.muted);
        sub.setPadding(0, dp(3), 0, 0);
        col.addView(sub);
        card.addView(col, new LinearLayout.LayoutParams(0, -2, 1));

        View badge = typeBadge(name, 11);
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(dp(40), dp(46));
        blp.leftMargin = dp(12);
        card.addView(badge, blp);

        if (!exists) {
            card.setAlpha(0.55f);
        } else {
            card.setOnClickListener(v -> {
                download(name);
                sub.setText(fmtSize(size) + " · 已下载");
            });
        }
        return card;
    }

    private boolean isChatAtBottom() {
        View child = chatScroll.getChildAt(0);
        return child == null || child.getBottom() - (chatScroll.getHeight() + chatScroll.getScrollY()) < dp(80);
    }

    private void scrollChatToBottom(boolean smooth) {
        chatScroll.post(() -> {
            View child = chatScroll.getChildAt(0);
            if (child == null) return;
            int y = Math.max(0, child.getBottom() - chatScroll.getHeight() + chatScroll.getPaddingBottom());
            if (smooth) chatScroll.smoothScrollTo(0, y);
            else chatScroll.scrollTo(0, y);
        });
    }

    private void sendText() {
        String text = input.getText().toString().trim();
        if (text.isEmpty() || !store.paired()) return;
        input.setText("");
        io.execute(() -> {
            try {
                Net.postJson(store, "/api/texts", new JSONObject().put("text", text));
                ui.post(() -> refresh(false));
            } catch (Exception e) {
                ui.post(() -> {
                    input.setText(text);
                    input.setSelection(text.length());
                    toast("没发出去：" + e.getMessage());
                });
            }
        });
    }

    /** 全屏查看图片，可保存到手机。 */
    private void showImage(String name) {
        Dialog d = new Dialog(this, android.R.style.Theme_Black_NoTitleBar_Fullscreen);
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        ImageView iv = new ImageView(this);
        iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
        root.addView(iv, new FrameLayout.LayoutParams(-1, -1));
        ProgressBar spin = new ProgressBar(this);
        root.addView(spin, new FrameLayout.LayoutParams(dp(40), dp(40), Gravity.CENTER));
        TextView save = new TextView(this);
        save.setText("保存到手机");
        save.setTextColor(Color.WHITE);
        save.setTextSize(15);
        save.setGravity(Gravity.CENTER);
        save.setPadding(dp(24), 0, dp(24), 0);
        GradientDrawable pill = new GradientDrawable();
        pill.setCornerRadius(dp(100));
        pill.setColor(0x33FFFFFF);
        save.setBackground(pill);
        FrameLayout.LayoutParams slp = new FrameLayout.LayoutParams(-2, dp(44), Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        slp.bottomMargin = dp(48);
        root.addView(save, slp);
        save.setOnClickListener(v -> {
            download(name);
            save.setText("已开始保存");
        });
        iv.setOnClickListener(v -> d.dismiss());
        d.setContentView(root);
        d.show();
        int target = Math.max(getResources().getDisplayMetrics().widthPixels, getResources().getDisplayMetrics().heightPixels);
        loadBitmap(name, target, "full", bm -> {
            spin.setVisibility(View.GONE);
            iv.setImageBitmap(bm);
        });
    }

    // ---------- 文件模式 ----------

    private void renderFilesEmpty(String text) {
        filesCount.setText("");
        filesBox.removeAllViews();
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER_HORIZONTAL);
        box.setPadding(dp(20), dp(32), dp(20), dp(32));
        ImageView icon = new ImageView(this);
        icon.setImageResource(R.drawable.ic_folder);
        icon.setImageTintList(ColorStateList.valueOf(getColor(R.color.faint)));
        box.addView(icon, new LinearLayout.LayoutParams(dp(36), dp(36)));
        TextView t = label(text, 13, R.color.muted);
        t.setGravity(Gravity.CENTER);
        t.setPadding(0, dp(10), 0, 0);
        box.addView(t);
        filesBox.addView(box);
    }

    private void renderFiles(JSONArray files) {
        String sig = files.toString();
        if (sig.equals(lastFiles)) return;
        lastFiles = sig;
        if (files.length() == 0) {
            renderFilesEmpty("电脑接收文件夹是空的");
            return;
        }
        filesCount.setText("电脑接收文件夹 · " + files.length() + " 个文件 · 长按可删除");
        filesBox.removeAllViews();
        for (int i = 0; i < files.length(); i++) {
            JSONObject f = files.optJSONObject(i);
            if (i > 0) filesBox.addView(divider());
            filesBox.addView(fileRow(f.optString("name"), f.optLong("size"), f.optDouble("mtime")));
        }
    }

    private View fileRow(String name, long size, double mtime) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(14), dp(10), dp(10), dp(10));
        row.setBackgroundResource(selectableBg());

        View thumb;
        if (isImage(name)) {
            ImageView iv = new ImageView(this);
            iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
            iv.setBackgroundResource(R.drawable.thumb_bg);
            iv.setClipToOutline(true);
            loadBitmap(name, dp(46), "thumb@" + mtime, bm -> iv.setImageBitmap(ThumbnailUtils.extractThumbnail(bm, dp(46), dp(46))));
            iv.setOnClickListener(v -> showImage(name));
            thumb = iv;
        } else {
            thumb = typeBadge(name, 11);
        }
        row.addView(thumb, new LinearLayout.LayoutParams(dp(46), dp(46)));

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(dp(14), 0, dp(10), 0);
        TextView title = label(name, 15, R.color.text);
        title.setSingleLine(true);
        title.setEllipsize(TextUtils.TruncateAt.MIDDLE);
        col.addView(title);
        TextView sub = label(fmtSize(size) + "  ·  " + fmtTime(mtime), 12, R.color.muted);
        sub.setPadding(0, dp(2), 0, 0);
        col.addView(sub);
        row.addView(col, new LinearLayout.LayoutParams(0, -2, 1));

        FrameLayout btn = new FrameLayout(this);
        btn.setBackgroundResource(R.drawable.circle_soft);
        ImageView icon = new ImageView(this);
        boolean done = downloaded.contains(name);
        icon.setImageResource(done ? R.drawable.ic_check : R.drawable.ic_download);
        icon.setImageTintList(ColorStateList.valueOf(getColor(done ? R.color.ok : R.color.accent)));
        btn.addView(icon, new FrameLayout.LayoutParams(dp(20), dp(20), Gravity.CENTER));
        row.addView(btn, new LinearLayout.LayoutParams(dp(38), dp(38)));

        View.OnClickListener dl = v -> {
            download(name);
            icon.setImageResource(R.drawable.ic_check);
            icon.setImageTintList(ColorStateList.valueOf(getColor(R.color.ok)));
        };
        row.setOnClickListener(dl);
        btn.setOnClickListener(dl);
        row.setOnLongClickListener(v -> {
            confirmDelete(name);
            return true;
        });
        return row;
    }

    // ---------- 图片加载 ----------

    interface BitmapCallback {
        void on(Bitmap bm);
    }

    /** 下载图片并按 target 像素缩小解码，结果缓存。 */
    private void loadBitmap(String name, int target, String tag, BitmapCallback cb) {
        String key = name + "#" + tag + "#" + target;
        Bitmap cached = thumbs.get(key);
        if (cached != null) {
            cb.on(cached);
            return;
        }
        thumbIo.execute(() -> {
            try {
                byte[] data = Net.fetchBytes(store, name, 60 * 1024 * 1024);
                BitmapFactory.Options o = new BitmapFactory.Options();
                o.inJustDecodeBounds = true;
                BitmapFactory.decodeByteArray(data, 0, data.length, o);
                int sample = 1;
                while (Math.min(o.outWidth, o.outHeight) / (sample * 2) >= target) sample *= 2;
                o = new BitmapFactory.Options();
                o.inSampleSize = sample;
                Bitmap bm = BitmapFactory.decodeByteArray(data, 0, data.length, o);
                if (bm == null) return;
                thumbs.put(key, bm);
                ui.post(() -> cb.on(bm));
            } catch (IOException | OutOfMemoryError ignored) {
            }
        });
    }

    // ---------- 操作 ----------

    private void download(String name) {
        DownloadManager.Request req = new DownloadManager.Request(Uri.parse(Net.downloadUrl(store, name)))
                .addRequestHeader("X-Key", store.key())
                .setTitle(name)
                .setDescription("来自电脑「" + store.name() + "」")
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, "手机互传/" + name);
        getSystemService(DownloadManager.class).enqueue(req);
        downloaded.add(name);
        toast("正在下载到 Download/手机互传");
    }

    private void confirmDelete(String name) {
        new Sheet(this)
                .icon(R.drawable.ic_trash, R.color.danger, R.drawable.circle_soft)
                .title("删除电脑上的文件？")
                .message(name + "\n文件会移到电脑的回收站，需要时可以还原。")
                .buttons("取消", null, "删除", () -> io.execute(() -> {
                    try {
                        Net.postJson(store, "/api/delete", new JSONObject().put("name", name));
                        ui.post(() -> {
                            lastFiles = "";
                            refresh(false);
                        });
                    } catch (Exception e) {
                        ui.post(() -> toast("删除失败：" + e.getMessage()));
                    }
                }), true)
                .show();
    }

    private void pickFiles() {
        if (!store.paired()) {
            findPc();
            return;
        }
        Intent i = new Intent(Intent.ACTION_GET_CONTENT);
        i.setType("*/*");
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        startActivityForResult(Intent.createChooser(i, "选择要发送的照片或文件"), REQ_PICK);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_PROJECT) {
            if (resultCode != RESULT_OK || data == null) return;
            Intent i = new Intent(this, MirrorService.class).putExtra("code", resultCode).putExtra("data", data);
            startForegroundService(i);
            toast(ControlService.enabled() ? "开始投屏，电脑上可以查看和操作手机" : "开始投屏，电脑上点「手机」查看");
            return;
        }
        if (requestCode != REQ_PICK || resultCode != RESULT_OK || data == null) return;
        ArrayList<Uri> uris = new ArrayList<>();
        ClipData clip = data.getClipData();
        if (clip != null) {
            for (int i = 0; i < clip.getItemCount(); i++) uris.add(clip.getItemAt(i).getUri());
        } else if (data.getData() != null) {
            uris.add(data.getData());
        }
        if (uris.isEmpty()) return;
        // 复用分享界面来上传
        Intent send = new Intent(this, ShareActivity.class);
        send.setAction(Intent.ACTION_SEND_MULTIPLE);
        send.setType("*/*");
        send.putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris);
        ClipData grant = ClipData.newRawUri("", uris.get(0));
        for (int i = 1; i < uris.size(); i++) grant.addItem(new ClipData.Item(uris.get(i)));
        send.setClipData(grant);
        send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        startActivity(send);
    }

    private void copy(String text) {
        getSystemService(ClipboardManager.class).setPrimaryClip(ClipData.newPlainText("文件互传", text));
        if (Build.VERSION.SDK_INT < 33) toast("已复制"); // 13 以上系统会自己提示
    }

    // ---------- 小工具 ----------

    private static boolean isImage(String name) {
        int dot = name.lastIndexOf('.');
        return dot >= 0 && name.substring(dot + 1).toLowerCase(Locale.ROOT).matches(IMAGE_EXT);
    }

    private static final String[][] TYPES = {
            {"heic|heif|raw|dng|psd", "#0EA5E9"},
            {"mp4|mov|mkv|avi|wmv|flv|webm|m4v|3gp", "#8B5CF6"},
            {"mp3|wav|flac|aac|m4a|ogg|amr|wma", "#DB2777"},
            {"pdf", "#DC2626"},
            {"doc|docx|rtf|odt|wps", "#2563EB"},
            {"xls|xlsx|csv|ods|et", "#16A34A"},
            {"ppt|pptx|odp|dps|key", "#EA580C"},
            {"zip|rar|7z|tar|gz|bz2|xz", "#B45309"},
            {"apk|apks|xapk", "#15803D"},
            {"exe|msi|bat|cmd", "#475569"},
    };

    private View typeBadge(String name, int sp) {
        String ext = name.contains(".") ? name.substring(name.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT) : "";
        String color = "#64748B";
        for (String[] t : TYPES) if (ext.matches(t[0])) color = t[1];
        TextView badge = new TextView(this);
        badge.setText(ext.isEmpty() ? "FILE" : ext.length() > 4 ? ext.substring(0, 4).toUpperCase(Locale.ROOT) : ext.toUpperCase(Locale.ROOT));
        badge.setTextSize(sp);
        badge.setTypeface(Typeface.DEFAULT_BOLD);
        badge.setTextColor(Color.WHITE);
        badge.setGravity(Gravity.CENTER);
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(10));
        bg.setColor(Color.parseColor(color));
        badge.setBackground(bg);
        return badge;
    }

    private TextView label(String text, int sp, int colorRes) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextSize(sp);
        t.setTextColor(getColor(colorRes));
        return t;
    }

    private View divider() {
        View v = new View(this);
        v.setBackgroundColor(getColor(R.color.line));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, Math.max(1, dp(1) / 2));
        lp.leftMargin = dp(74);
        lp.rightMargin = dp(14);
        v.setLayoutParams(lp);
        return v;
    }

    private View padded(View child) {
        FrameLayout wrap = new FrameLayout(this);
        wrap.setPadding(dp(22), dp(8), dp(22), 0);
        wrap.addView(child);
        return wrap;
    }

    private int selectableBg() {
        TypedValue tv = new TypedValue();
        getTheme().resolveAttribute(android.R.attr.selectableItemBackground, tv, true);
        return tv.resourceId;
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private void toast(String msg) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
    }

    static String fmtSize(long n) {
        String[] units = {"B", "KB", "MB", "GB", "TB"};
        double v = n;
        int i = 0;
        while (v >= 1024 && i < units.length - 1) {
            v /= 1024;
            i++;
        }
        return i == 0 ? n + " B" : String.format(Locale.US, v < 10 ? "%.2f %s" : "%.1f %s", v, units[i]);
    }

    private static String fmtTime(double sec) {
        Date d = new Date((long) (sec * 1000));
        boolean today = new SimpleDateFormat("yyyyMMdd", Locale.US).format(d)
                .equals(new SimpleDateFormat("yyyyMMdd", Locale.US).format(new Date()));
        return new SimpleDateFormat(today ? "HH:mm" : "M月d日 HH:mm", Locale.CHINA).format(d);
    }

    /** 聊天里的时间分隔：今天只显示时间，昨天、一周内显示星期，更早显示日期。 */
    private static String fmtChatTime(double sec) {
        Calendar now = Calendar.getInstance(), c = Calendar.getInstance();
        c.setTimeInMillis((long) (sec * 1000));
        String hm = new SimpleDateFormat("HH:mm", Locale.CHINA).format(c.getTime());
        Calendar startToday = (Calendar) now.clone();
        startToday.set(Calendar.HOUR_OF_DAY, 0);
        startToday.set(Calendar.MINUTE, 0);
        startToday.set(Calendar.SECOND, 0);
        startToday.set(Calendar.MILLISECOND, 0);
        long days = (startToday.getTimeInMillis() - c.getTimeInMillis()) / 86_400_000L + (c.before(startToday) ? 1 : 0);
        if (!c.before(startToday)) return hm;
        if (days == 1) return "昨天 " + hm;
        if (days < 7) return new SimpleDateFormat("EEEE HH:mm", Locale.CHINA).format(c.getTime());
        boolean sameYear = c.get(Calendar.YEAR) == now.get(Calendar.YEAR);
        return new SimpleDateFormat(sameYear ? "M月d日 HH:mm" : "yyyy年M月d日 HH:mm", Locale.CHINA).format(c.getTime());
    }
}
