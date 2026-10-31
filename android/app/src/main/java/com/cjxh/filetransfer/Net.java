package com.cjxh.filetransfer;

import android.net.Uri;
import android.os.Build;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.InterfaceAddress;
import java.net.NetworkInterface;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 与电脑客户端通信：UDP 广播发现、配对、HTTP 接口。所有方法都会阻塞，需在后台线程调用。 */
final class Net {
    static final int DISCOVERY_PORT = 8766;
    private static final byte[] DISCOVER_MAGIC = "FT_DISCOVER".getBytes(StandardCharsets.US_ASCII);

    static final class Pc {
        String ip, name, id;
        int port;
        boolean paired;

        @Override
        public String toString() {
            return name + "  (" + ip + ")";
        }
    }

    interface Progress {
        void on(long done, long total);
    }

    static String deviceName() {
        String maker = Build.MANUFACTURER, model = Build.MODEL;
        if (model.toLowerCase().startsWith(maker.toLowerCase())) return model;
        return Character.toUpperCase(maker.charAt(0)) + maker.substring(1) + " " + model;
    }

    // ---------- 发现 ----------

    /** 向局域网广播，收集在 timeoutMs 内应答的电脑。 */
    static List<Pc> discover(int timeoutMs) {
        Map<String, Pc> found = new LinkedHashMap<>();
        List<InetAddress> targets = broadcastAddresses();
        try (DatagramSocket socket = new DatagramSocket()) {
            socket.setBroadcast(true);
            socket.setSoTimeout(250);
            long end = System.currentTimeMillis() + timeoutMs;
            long nextSend = 0;
            byte[] buf = new byte[2048];
            while (System.currentTimeMillis() < end) {
                if (System.currentTimeMillis() >= nextSend) {
                    for (InetAddress addr : targets) {
                        try {
                            socket.send(new DatagramPacket(DISCOVER_MAGIC, DISCOVER_MAGIC.length, addr, DISCOVERY_PORT));
                        } catch (IOException ignored) {
                        }
                    }
                    nextSend = System.currentTimeMillis() + 500; // UDP 可能丢包，多发几次
                }
                DatagramPacket packet = new DatagramPacket(buf, buf.length);
                try {
                    socket.receive(packet);
                } catch (SocketTimeoutException e) {
                    continue;
                }
                try {
                    JSONObject o = new JSONObject(new String(packet.getData(), 0, packet.getLength(), StandardCharsets.UTF_8));
                    if (!"ft".equals(o.optString("app"))) continue;
                    Pc pc = new Pc();
                    pc.ip = packet.getAddress().getHostAddress();
                    pc.port = o.optInt("port", 8765);
                    pc.name = o.optString("name", pc.ip);
                    pc.id = o.optString("id");
                    if (!found.containsKey(pc.id)) found.put(pc.id, pc);
                } catch (JSONException ignored) {
                }
            }
        } catch (IOException ignored) {
        }
        return new ArrayList<>(found.values());
    }

    private static List<InetAddress> broadcastAddresses() {
        List<InetAddress> list = new ArrayList<>();
        try {
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp() || ni.isLoopback()) continue;
                for (InterfaceAddress ia : ni.getInterfaceAddresses()) {
                    if (ia.getBroadcast() != null) list.add(ia.getBroadcast());
                }
            }
        } catch (IOException ignored) {
        }
        try {
            list.add(InetAddress.getByName("255.255.255.255"));
        } catch (IOException ignored) {
        }
        return list;
    }

    /** 访问 /api/hello，确认对方是电脑客户端；key 非空时顺便检查是否仍处于配对状态。 */
    static Pc hello(String ip, int port, String key) {
        try {
            HttpURLConnection c = open("http://" + ip + ":" + port + "/api/hello", "GET", key);
            c.setConnectTimeout(1500);
            c.setReadTimeout(3000);
            JSONObject o = new JSONObject(readBody(c));
            if (!"ft".equals(o.optString("app"))) return null;
            Pc pc = new Pc();
            pc.ip = ip;
            pc.port = port;
            pc.name = o.optString("name", ip);
            pc.id = o.optString("id");
            pc.paired = o.optBoolean("paired");
            return pc;
        } catch (IOException | JSONException e) {
            return null;
        }
    }

    /** 请求配对，电脑端会弹窗确认（最多等 60 秒），成功返回密钥。 */
    static String pair(Pc pc) throws IOException {
        HttpURLConnection c = open("http://" + pc.ip + ":" + pc.port + "/api/pair", "POST", "");
        c.setReadTimeout(70_000);
        try {
            JSONObject body = new JSONObject().put("device", deviceName());
            JSONObject res = new JSONObject(send(c, body.toString()));
            return res.getString("key");
        } catch (JSONException e) {
            throw new IOException("电脑返回了无法识别的数据");
        }
    }

    /** 确认已配对的电脑可用；IP 变了（比如重连 Wi-Fi）就重新广播找回来。 */
    static boolean ensure(Store store) {
        if (!store.paired()) return false;
        Pc pc = hello(store.ip(), store.port(), store.key());
        if (pc != null && pc.paired && pc.id.equals(store.id())) return true;
        for (Pc found : discover(1500)) {
            if (found.id.equals(store.id())) {
                store.updateAddress(found.ip, found.port);
                return true;
            }
        }
        return false;
    }

    // ---------- HTTP 接口 ----------

    static JSONArray getArray(Store store, String path) throws IOException {
        HttpURLConnection c = open(store.base() + path, "GET", store.key());
        try {
            return new JSONArray(readBody(c));
        } catch (JSONException e) {
            throw new IOException("数据格式错误");
        }
    }

    static void postJson(Store store, String path, JSONObject body) throws IOException {
        send(open(store.base() + path, "POST", store.key()), body.toString());
    }

    static void upload(Store store, String name, InputStream in, long size, Progress progress) throws IOException {
        HttpURLConnection c = open(store.base() + "/api/upload?name=" + Uri.encode(name), "POST", store.key());
        c.setDoOutput(true);
        c.setFixedLengthStreamingMode(size);
        c.setRequestProperty("Content-Type", "application/octet-stream");
        c.setReadTimeout(60_000);
        byte[] buf = new byte[256 * 1024];
        long done = 0;
        try (OutputStream out = c.getOutputStream()) {
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
                done += n;
                progress.on(done, size);
            }
        }
        readBody(c);
    }

    static String downloadUrl(Store store, String name) {
        return store.base() + "/dl/" + Uri.encode(name);
    }

    /** 下载用于缩略图的原图，超过 maxBytes 放弃。 */
    static byte[] fetchBytes(Store store, String name, int maxBytes) throws IOException {
        HttpURLConnection c = open(downloadUrl(store, name) + "?inline=1", "GET", store.key());
        c.setReadTimeout(15_000);
        if (c.getResponseCode() != 200 || c.getContentLengthLong() > maxBytes) {
            c.disconnect();
            throw new IOException("skip");
        }
        try (InputStream in = c.getInputStream()) {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) {
                bos.write(buf, 0, n);
                if (bos.size() > maxBytes) throw new IOException("too large");
            }
            return bos.toByteArray();
        }
    }

    static HttpURLConnection open(String url, String method, String key) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setRequestMethod(method);
        c.setConnectTimeout(3000);
        c.setReadTimeout(10_000);
        c.setRequestProperty("X-Device", Uri.encode(deviceName()));
        if (!key.isEmpty()) c.setRequestProperty("X-Key", key);
        return c;
    }

    private static String send(HttpURLConnection c, String json) throws IOException {
        byte[] data = json.getBytes(StandardCharsets.UTF_8);
        c.setDoOutput(true);
        c.setFixedLengthStreamingMode(data.length);
        c.setRequestProperty("Content-Type", "application/json; charset=utf-8");
        try (OutputStream out = c.getOutputStream()) {
            out.write(data);
        }
        return readBody(c);
    }

    /** 读取响应；出错时抛出带服务器错误信息的 IOException。 */
    static String readBody(HttpURLConnection c) throws IOException {
        int code = c.getResponseCode();
        InputStream in = code < 400 ? c.getInputStream() : c.getErrorStream();
        String body = "";
        if (in != null) {
            try (InputStream is = in) {
                ByteArrayOutputStream bos = new ByteArrayOutputStream();
                byte[] buf = new byte[8192];
                int n;
                while ((n = is.read(buf)) > 0) bos.write(buf, 0, n);
                body = bos.toString("UTF-8");
            }
        }
        if (code >= 400) {
            String msg = "HTTP " + code;
            try {
                msg = new JSONObject(body).optString("error", msg);
            } catch (JSONException ignored) {
            }
            if (code == 403 && msg.startsWith("HTTP")) msg = "配对已失效，请重新连接电脑";
            throw new IOException(msg);
        }
        return body;
    }

    private Net() {
    }
}
