package com.cjxh.filetransfer;

import android.content.Context;
import android.content.SharedPreferences;

/** 保存已配对的电脑：地址、端口、密钥。 */
final class Store {
    private final SharedPreferences sp;

    Store(Context context) {
        sp = context.getSharedPreferences("pc", Context.MODE_PRIVATE);
    }

    String ip() { return sp.getString("ip", ""); }
    int port() { return sp.getInt("port", 8765); }
    String key() { return sp.getString("key", ""); }
    String name() { return sp.getString("name", ""); }
    String id() { return sp.getString("id", ""); }

    boolean paired() { return !key().isEmpty(); }

    String base() { return "http://" + ip() + ":" + port(); }

    void save(Net.Pc pc, String key) {
        sp.edit()
                .putString("ip", pc.ip).putInt("port", pc.port)
                .putString("name", pc.name).putString("id", pc.id)
                .putString("key", key)
                .apply();
    }

    void updateAddress(String ip, int port) {
        sp.edit().putString("ip", ip).putInt("port", port).apply();
    }

    void clear() {
        sp.edit().clear().apply();
    }
}
