package com.cjxh.filetransfer;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.graphics.Bitmap;
import android.graphics.PixelFormat;
import android.graphics.Point;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.SystemClock;
import android.util.DisplayMetrics;
import android.view.Display;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

/**
 * 投屏到电脑：用系统录屏接口拿到画面，压成 JPEG 连续推给电脑；
 * 同时接收电脑发来的操作，交给无障碍服务执行。
 */
public class MirrorService extends Service {
    static final String ACTION_STOP = "stop";
    private static final String CHANNEL = "mirror";
    private static final int ID = 2;
    private static final int LONG_SIDE = 1280;       // 画面最长边，兼顾清晰度和流量
    private static final long FRAME_INTERVAL = 80;   // 最多约 12 帧/秒

    static volatile boolean running;

    private Store store;
    private MediaProjection projection;
    private VirtualDisplay display;
    private ImageReader reader;
    private HandlerThread imageThread;
    private Handler imageHandler;
    private int width, height, rotation = -1;
    private long lastFrameAt;

    private final Object frameLock = new Object();
    private byte[] latest;
    private volatile boolean active;
    private volatile HttpURLConnection pushConn, controlConn;

    @Override
    public void onCreate() {
        super.onCreate();
        store = new Store(this);
        NotificationChannel ch = new NotificationChannel(CHANNEL, "投屏到电脑", NotificationManager.IMPORTANCE_LOW);
        getSystemService(NotificationManager.class).createNotificationChannel(ch);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null || ACTION_STOP.equals(intent.getAction())) {
            stopSelf();
            return START_NOT_STICKY;
        }
        Intent stop = new Intent(this, MirrorService.class).setAction(ACTION_STOP);
        Notification n = new Notification.Builder(this, CHANNEL)
                .setSmallIcon(R.drawable.ic_launcher_fg)
                .setContentTitle("正在投屏到电脑「" + store.name() + "」")
                .setContentText(ControlService.enabled() ? "电脑可以查看和操作这台手机" : "电脑可以查看这台手机的屏幕")
                .setOngoing(true)
                .addAction(new Notification.Action.Builder(null, "停止投屏",
                        PendingIntent.getService(this, 1, stop, PendingIntent.FLAG_IMMUTABLE)).build())
                .build();
        // 安卓 14 起必须先以「录屏」类型进入前台，才能拿到录屏权限
        startForeground(ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION);

        if (active) return START_NOT_STICKY;
        Intent data = Build.VERSION.SDK_INT >= 33
                ? intent.getParcelableExtra("data", Intent.class)
                : intent.getParcelableExtra("data");
        int code = intent.getIntExtra("code", 0);
        if (data == null) {
            stopSelf();
            return START_NOT_STICKY;
        }
        imageThread = new HandlerThread("mirror-capture");
        imageThread.start();
        imageHandler = new Handler(imageThread.getLooper());
        projection = getSystemService(MediaProjectionManager.class).getMediaProjection(code, data);
        projection.registerCallback(new MediaProjection.Callback() {
            @Override
            public void onStop() {
                stopSelf(); // 用户在系统里结束了录屏
            }
        }, imageHandler);

        active = true;
        running = true;
        imageHandler.post(this::setupDisplay);
        getSystemService(DisplayManager.class).registerDisplayListener(rotationListener, imageHandler);
        new Thread(this::pushLoop, "mirror-push").start();
        new Thread(this::controlLoop, "mirror-control").start();
        return START_NOT_STICKY;
    }

    @Override
    public void onDestroy() {
        active = false;
        running = false;
        getSystemService(DisplayManager.class).unregisterDisplayListener(rotationListener);
        HttpURLConnection p = pushConn, c = controlConn;
        if (p != null) p.disconnect();
        if (c != null) c.disconnect();
        synchronized (frameLock) {
            frameLock.notifyAll();
        }
        if (imageHandler != null) {
            imageHandler.post(() -> {
                if (display != null) display.release();
                if (reader != null) reader.close();
                if (projection != null) projection.stop();
                imageThread.quitSafely();
            });
        }
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    // ---------- 截取画面 ----------

    /** 按当前屏幕方向创建（或调整）虚拟屏幕，手机横过来时画面也跟着横 */
    private void setupDisplay() {
        Display d = getSystemService(DisplayManager.class).getDisplay(Display.DEFAULT_DISPLAY);
        Point real = new Point();
        d.getRealSize(real);
        DisplayMetrics dm = new DisplayMetrics();
        d.getRealMetrics(dm);
        rotation = d.getRotation();
        float scale = Math.min(1f, LONG_SIDE / (float) Math.max(real.x, real.y));
        width = Math.round(real.x * scale) / 2 * 2;
        height = Math.round(real.y * scale) / 2 * 2;
        ImageReader old = reader;
        reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2);
        reader.setOnImageAvailableListener(this::onImage, imageHandler);
        if (display == null) {
            display = projection.createVirtualDisplay("LanDrop", width, height, dm.densityDpi,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, reader.getSurface(), null, imageHandler);
        } else {
            display.resize(width, height, dm.densityDpi);
            display.setSurface(reader.getSurface());
        }
        if (old != null) old.close();
    }

    private final DisplayManager.DisplayListener rotationListener = new DisplayManager.DisplayListener() {
        @Override public void onDisplayAdded(int id) {}
        @Override public void onDisplayRemoved(int id) {}

        @Override
        public void onDisplayChanged(int id) {
            if (id != Display.DEFAULT_DISPLAY || display == null) return;
            int r = getSystemService(DisplayManager.class).getDisplay(Display.DEFAULT_DISPLAY).getRotation();
            if (r != rotation) setupDisplay();
        }
    };

    private void onImage(ImageReader r) {
        Image img = r.acquireLatestImage();
        if (img == null) return;
        try {
            long now = SystemClock.uptimeMillis();
            if (now - lastFrameAt < FRAME_INTERVAL) return; // 限制帧率，省电省流量
            lastFrameAt = now;
            Image.Plane plane = img.getPlanes()[0];
            ByteBuffer buf = plane.getBuffer();
            int pixelStride = plane.getPixelStride(), rowStride = plane.getRowStride();
            int w = img.getWidth(), h = img.getHeight();
            Bitmap bmp = Bitmap.createBitmap(rowStride / pixelStride, h, Bitmap.Config.ARGB_8888);
            bmp.copyPixelsFromBuffer(buf);
            Bitmap frame = bmp.getWidth() == w ? bmp : Bitmap.createBitmap(bmp, 0, 0, w, h);
            ByteArrayOutputStream out = new ByteArrayOutputStream(160 * 1024);
            frame.compress(Bitmap.CompressFormat.JPEG, 70, out);
            if (frame != bmp) frame.recycle();
            bmp.recycle();
            synchronized (frameLock) {
                latest = out.toByteArray();
                frameLock.notifyAll();
            }
        } catch (RuntimeException ignored) {
        } finally {
            img.close();
        }
    }

    // ---------- 推送画面 / 接收操作 ----------

    private void pushLoop() {
        while (active) {
            try {
                HttpURLConnection c = Net.open(store.base() + "/api/mirror/push", "POST", store.key());
                c.setDoOutput(true);
                c.setChunkedStreamingMode(0);
                c.setRequestProperty("Content-Type", "application/octet-stream");
                pushConn = c;
                DataOutputStream out = new DataOutputStream(c.getOutputStream());
                byte[] sent = null;
                while (active) {
                    byte[] frame;
                    synchronized (frameLock) {
                        while (active && latest == sent) frameLock.wait(2000);
                        frame = latest;
                    }
                    if (!active) break;
                    if (frame == null || frame == sent) continue;
                    out.writeInt(frame.length);
                    out.write(frame);
                    out.flush();
                    sent = frame;
                }
                out.close();
                c.getResponseCode();
            } catch (Exception e) {
                if (!active) return;
                SystemClock.sleep(2000);
                Net.ensure(store);
                synchronized (frameLock) {
                    // 重连后把当前画面重新发一次
                    if (latest != null) latest = latest.clone();
                }
            }
        }
    }

    private void controlLoop() {
        while (active) {
            try {
                HttpURLConnection c = Net.open(store.base() + "/api/mirror/control", "GET", store.key());
                c.setReadTimeout(30_000); // 电脑每 10 秒发一次心跳
                controlConn = c;
                BufferedReader in = new BufferedReader(new InputStreamReader(c.getInputStream(), StandardCharsets.UTF_8));
                String line;
                while (active && (line = in.readLine()) != null) {
                    if (line.trim().isEmpty()) continue;
                    JSONObject e = new JSONObject(line);
                    if ("stop".equals(e.optString("t"))) {
                        stopSelf();
                        return;
                    }
                    ControlService.dispatch(e);
                }
            } catch (Exception e) {
                if (!active) return;
                SystemClock.sleep(2000);
            }
        }
    }
}
