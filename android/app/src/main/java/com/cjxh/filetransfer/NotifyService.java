package com.cjxh.filetransfer;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;

import org.json.JSONArray;
import org.json.JSONObject;

import java.net.HttpURLConnection;

/**
 * 后台接收通知：用长轮询（电脑没有新消息时会把请求挂住最多 25 秒）等待电脑发来的东西，
 * 收到文字、图片、文件时弹通知。App 在前台时不弹，由聊天界面直接显示。
 */
public class NotifyService extends Service {
    private static final String CH_SERVICE = "service";
    private static final String CH_MESSAGES = "messages";
    private static final int ID_SERVICE = 1;

    /** 聊天界面是否在前台（在前台时不弹通知） */
    static volatile boolean appVisible;

    private volatile boolean running;
    private Thread worker;

    static boolean enabled(Context c) {
        return c.getSharedPreferences("pc", MODE_PRIVATE).getBoolean("notify", true);
    }

    static void setEnabled(Context c, boolean on) {
        c.getSharedPreferences("pc", MODE_PRIVATE).edit().putBoolean("notify", on).apply();
        if (on) start(c);
        else c.stopService(new Intent(c, NotifyService.class));
    }

    static void start(Context c) {
        if (!enabled(c) || !new Store(c).paired()) return;
        try {
            c.startForegroundService(new Intent(c, NotifyService.class));
        } catch (RuntimeException ignored) {
            // 后台时系统不允许启动前台服务，下次打开 App 再启动
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        NotificationManager nm = getSystemService(NotificationManager.class);
        NotificationChannel service = new NotificationChannel(CH_SERVICE, "后台接收", NotificationManager.IMPORTANCE_MIN);
        service.setDescription("保持和电脑的连接，用来及时收到消息。可以在系统设置里隐藏这条通知");
        service.setShowBadge(false);
        nm.createNotificationChannel(service);
        NotificationChannel messages = new NotificationChannel(CH_MESSAGES, "电脑发来的消息", NotificationManager.IMPORTANCE_HIGH);
        messages.setDescription("电脑发来文字、图片或文件时提醒");
        nm.createNotificationChannel(messages);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Notification n = new Notification.Builder(this, CH_SERVICE)
                .setSmallIcon(R.drawable.ic_launcher_fg)
                .setContentTitle("文件互传正在后台接收")
                .setContentText("电脑发来消息时会提醒你")
                .setContentIntent(openApp())
                .setOngoing(true)
                .build();
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(ID_SERVICE, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_REMOTE_MESSAGING);
        } else {
            startForeground(ID_SERVICE, n);
        }
        if (!running) {
            running = true;
            worker = new Thread(this::loop, "notify-poll");
            worker.start();
        }
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        running = false;
        if (worker != null) worker.interrupt();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void loop() {
        Store store = new Store(this);
        long lastSeq = -1;
        int failures = 0;
        while (running) {
            try {
                if (!store.paired()) {
                    stopSelf();
                    return;
                }
                if (failures >= 3) {
                    Net.ensure(store); // 电脑换了 IP 时重新找回来
                    failures = 0;
                }
                if (lastSeq < 0) {
                    // 第一次只记住当前进度，不把旧消息再提醒一遍
                    lastSeq = maxSeq(Net.getArray(store, "/api/messages?after=0"), 0);
                    continue;
                }
                HttpURLConnection c = Net.open(store.base() + "/api/messages?after=" + lastSeq + "&wait=25", "GET", store.key());
                c.setReadTimeout(40_000);
                JSONArray list = new JSONArray(Net.readBody(c));
                for (int i = 0; i < list.length(); i++) {
                    JSONObject m = list.getJSONObject(i);
                    long seq = m.optLong("seq");
                    if (seq <= lastSeq) continue;
                    lastSeq = seq;
                    if ("pc".equals(m.optString("side")) && !appVisible) show(store, m);
                }
                failures = 0;
            } catch (Exception e) {
                failures++;
                sleep(failures < 3 ? 3000 : 15000);
            }
        }
    }

    private static long maxSeq(JSONArray list, long def) {
        long max = def;
        for (int i = 0; i < list.length(); i++) max = Math.max(max, list.optJSONObject(i).optLong("seq"));
        return max;
    }

    private void show(Store store, JSONObject m) {
        String kind = m.optString("kind");
        String body;
        if ("text".equals(kind)) {
            body = m.optString("text");
        } else if ("file".equals(kind)) {
            String name = m.optString("name");
            body = (name.matches("(?i).*\\.(jpe?g|png|gif|webp|bmp|heic|avif)$") ? "[图片] " : "[文件] ") + name;
        } else {
            return;
        }
        Notification n = new Notification.Builder(this, CH_MESSAGES)
                .setSmallIcon(R.drawable.ic_launcher_fg)
                .setContentTitle("来自电脑「" + store.name() + "」")
                .setContentText(body)
                .setStyle(new Notification.BigTextStyle().bigText(body))
                .setContentIntent(openApp())
                .setAutoCancel(true)
                .setCategory(Notification.CATEGORY_MESSAGE)
                .build();
        try {
            getSystemService(NotificationManager.class).notify((int) (m.optLong("seq") % 100000) + 10, n);
        } catch (SecurityException ignored) {
            // 没有通知权限
        }
    }

    private PendingIntent openApp() {
        Intent i = new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        return PendingIntent.getActivity(this, 0, i, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ignored) {
        }
    }
}
