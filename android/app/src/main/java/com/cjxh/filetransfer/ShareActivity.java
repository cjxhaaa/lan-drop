package com.cjxh.filetransfer;

import android.app.Activity;
import android.content.ContentResolver;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.OpenableColumns;
import android.view.View;
import android.content.res.ColorStateList;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.TextView;

import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** 系统分享菜单里的「发送到电脑」：把分享来的文件/文字直接传给已配对的电脑。 */
public class ShareActivity extends Activity {
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final List<Uri> uris = new ArrayList<>();
    private String text;
    private int nextIndex; // 重试时从失败的那个文件继续
    private volatile boolean cancelled;
    private volatile InputStream current;
    private Store store;

    private TextView title, message, detail;
    private ProgressBar progress;
    private TextView primary, secondary;
    private ImageView icon;
    private View iconWrap;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_share);
        setFinishOnTouchOutside(false);
        store = new Store(this);
        title = findViewById(R.id.title);
        message = findViewById(R.id.message);
        detail = findViewById(R.id.detail);
        progress = findViewById(R.id.progress);
        primary = findViewById(R.id.btn_primary);
        secondary = findViewById(R.id.btn_secondary);
        icon = findViewById(R.id.icon);
        iconWrap = findViewById(R.id.icon_wrap);
        primary.setOnClickListener(v -> cancel());

        readIntent(getIntent());
        if (uris.isEmpty() && (text == null || text.isEmpty())) {
            fail(L.t("没有可发送的内容"), "", false);
            return;
        }
        io.execute(this::run);
    }

    @SuppressWarnings("deprecation")
    private void readIntent(Intent intent) {
        if (Intent.ACTION_SEND_MULTIPLE.equals(intent.getAction())) {
            ArrayList<Uri> list = Build.VERSION.SDK_INT >= 33
                    ? intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri.class)
                    : intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM);
            if (list != null) uris.addAll(list);
        } else {
            Uri uri = Build.VERSION.SDK_INT >= 33
                    ? intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri.class)
                    : intent.getParcelableExtra(Intent.EXTRA_STREAM);
            if (uri != null) uris.add(uri);
        }
        CharSequence t = intent.getCharSequenceExtra(Intent.EXTRA_TEXT);
        if (t != null) text = t.toString().trim();
    }

    private void run() {
        if (!store.paired()) {
            ui.post(this::needPair);
            return;
        }
        ui.post(() -> working(L.f("正在连接「%s」…", store.name()), ""));
        if (!Net.ensure(store)) {
            ui.post(() -> fail(L.f("找不到电脑「%s」", store.name()),
                    L.t("请确认电脑上的「文件互传」客户端正在运行，并且手机和电脑在同一个 Wi-Fi。"), true));
            return;
        }
        try {
            if (uris.isEmpty()) {
                Net.postJson(store, "/api/texts", new JSONObject().put("text", text));
                ui.post(() -> success(L.f("文字已发送到「%s」", store.name())));
                return;
            }
            ContentResolver resolver = getContentResolver();
            for (; nextIndex < uris.size(); nextIndex++) {
                if (cancelled) return;
                sendOne(resolver, uris.get(nextIndex), nextIndex);
            }
            int n = uris.size();
            ui.post(() -> success(n == 1 ? L.f("已发送到「%s」", store.name()) : L.f("已发送 %d 个文件到「%s」", n, store.name())));
        } catch (Exception e) {
            if (cancelled) return;
            String msg = e.getMessage() == null ? e.toString() : e.getMessage();
            ui.post(() -> fail(L.t("发送失败"), msg, true));
        }
    }

    private void sendOne(ContentResolver resolver, Uri uri, int index) throws IOException {
        String name = null;
        long size = -1;
        try (Cursor c = resolver.query(uri, new String[]{OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE}, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                if (!c.isNull(0)) name = c.getString(0);
                if (!c.isNull(1)) size = c.getLong(1);
            }
        } catch (RuntimeException ignored) {
        }
        if (name == null || name.isEmpty()) {
            name = uri.getLastPathSegment() == null ? "file" : new File(uri.getLastPathSegment()).getName();
        }
        String label = uris.size() > 1 ? L.f("（%d/%d）", index + 1, uris.size()) : "";
        String fName = name;
        ui.post(() -> working(L.t("正在发送") + label, fName));

        File tmp = null;
        InputStream in = resolver.openInputStream(uri);
        if (in == null) throw new IOException(L.t("无法读取文件：") + name);
        try {
            if (size < 0) {
                // 部分 App 不提供文件大小，先复制到缓存里再按固定长度上传
                tmp = File.createTempFile("share", ".tmp", getCacheDir());
                try (OutputStream out = new FileOutputStream(tmp)) {
                    byte[] buf = new byte[256 * 1024];
                    int n;
                    while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                }
                in.close();
                in = new FileInputStream(tmp);
                size = tmp.length();
            }
            current = in;
            long start = SystemClock.elapsedRealtime();
            long[] lastUi = {0};
            Net.upload(store, name, in, size, (done, total) -> {
                long now = SystemClock.elapsedRealtime();
                if (now - lastUi[0] < 120 && done < total) return;
                lastUi[0] = now;
                double speed = done * 1000.0 / Math.max(1, now - start);
                String info = fName + "\n" + MainActivity.fmtSize(done) + " / " + MainActivity.fmtSize(total)
                        + " · " + MainActivity.fmtSize((long) speed) + "/s";
                int p = total > 0 ? (int) (done * 1000 / total) : 0;
                ui.post(() -> {
                    progress.setIndeterminate(false);
                    progress.setProgress(p);
                    detail.setText(info);
                });
            });
        } finally {
            current = null;
            in.close();
            if (tmp != null) tmp.delete();
        }
    }

    // ---------- 界面状态 ----------

    /** 主要操作用实心按钮，其余用浅色按钮。 */
    private void style(TextView button, boolean filled) {
        button.setBackgroundResource(filled ? R.drawable.pill_primary : R.drawable.pill_soft);
        button.setTextColor(filled ? 0xFFFFFFFF : getColor(R.color.accent));
    }

    private void setDetail(String text) {
        detail.setText(text);
        detail.setVisibility(text == null || text.isEmpty() ? View.GONE : View.VISIBLE);
    }

    private void setIcon(int res, int color, int bg) {
        icon.setImageResource(res);
        icon.setImageTintList(ColorStateList.valueOf(getColor(color)));
        iconWrap.setBackgroundResource(bg);
    }

    private void working(String msg, String sub) {
        setIcon(R.drawable.ic_upload, R.color.accent, R.drawable.circle_soft);
        title.setText(L.t("发送到电脑"));
        message.setText(msg);
        setDetail(sub);
        progress.setVisibility(View.VISIBLE);
        progress.setIndeterminate(true);
        primary.setText(L.t("取消"));
        style(primary, false);
        primary.setOnClickListener(v -> cancel());
        secondary.setVisibility(View.GONE);
    }

    private void success(String msg) {
        setIcon(R.drawable.ic_check, R.color.ok, R.drawable.circle_ok);
        title.setText(L.t("发送成功"));
        message.setText(msg);
        setDetail("");
        progress.setIndeterminate(false);
        progress.setProgress(1000);
        primary.setText(L.t("完成"));
        style(primary, true);
        primary.setOnClickListener(v -> finish());
        ui.postDelayed(this::finish, 1500);
    }

    private void fail(String msg, String sub, boolean retry) {
        setIcon(R.drawable.ic_close, R.color.danger, R.drawable.circle_soft);
        title.setText(L.t("没有发送成功"));
        message.setText(msg);
        setDetail(sub);
        progress.setVisibility(View.GONE);
        primary.setText(L.t("关闭"));
        style(primary, !retry);
        primary.setOnClickListener(v -> finish());
        if (retry) {
            secondary.setVisibility(View.VISIBLE);
            secondary.setText(L.t("重试"));
            style(secondary, true);
            secondary.setOnClickListener(v -> {
                working(L.t("正在重试…"), "");
                io.execute(this::run);
            });
        }
    }

    private void needPair() {
        setIcon(R.drawable.ic_computer, R.color.accent, R.drawable.circle_soft);
        title.setText(L.t("还没有连接电脑"));
        message.setText(L.t("请先打开「文件互传」App，查找并连接电脑，之后就能从任意 App 直接分享过来。"));
        progress.setVisibility(View.GONE);
        primary.setText(L.t("打开 App"));
        style(primary, true);
        primary.setOnClickListener(v -> {
            startActivity(new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            finish();
        });
        secondary.setVisibility(View.VISIBLE);
        secondary.setText(L.t("取消"));
        style(secondary, false);
        secondary.setOnClickListener(v -> finish());
    }

    private void cancel() {
        cancelled = true;
        InputStream in = current;
        if (in != null) {
            try {
                in.close(); // 让上传线程尽快中断
            } catch (IOException ignored) {
            }
        }
        finish();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        io.shutdownNow();
    }
}
