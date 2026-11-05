package com.watchunlock.app;

import android.content.Context;
import android.content.SharedPreferences;

/** 轻量配置：设备选择、解锁条件、PIN。真正下发给守护的配置写在 config.conf。 */
public final class Prefs {

    private Prefs() {
    }

    private static SharedPreferences sp(Context c) {
        return c.getSharedPreferences("wu", Context.MODE_PRIVATE);
    }

    /** 设备列表，每条「启用|MAC|名称」，用 ; 分隔 */
    public static String targets(Context c) {
        return sp(c).getString("targets", "");
    }

    public static void saveTargets(Context c, String v) {
        sp(c).edit().putString("targets", v).apply();
    }

    /** WiFi 列表，每条「启用|SSID」，用 ; 分隔 */
    public static String wifiList(Context c) {
        return sp(c).getString("wifi_list", "");
    }

    public static void saveWifiList(Context c, String v) {
        sp(c).edit().putString("wifi_list", v).apply();
    }

    /** 多设备模式：所有已启用设备都必须在线（false = 任一在线） */
    public static boolean modeAll(Context c) {
        return sp(c).getBoolean("mode_all", false);
    }

    public static void setModeAll(Context c, boolean v) {
        sp(c).edit().putBoolean("mode_all", v).apply();
    }

    /** WiFi 作为必要条件（蓝牙在线且必须连着已启用的 SSID 之一） */
    public static boolean wifiRequired(Context c) {
        return sp(c).getBoolean("wifi_required", false);
    }

    public static void setWifiRequired(Context c, boolean v) {
        sp(c).edit().putBoolean("wifi_required", v).apply();
    }

    /** WiFi 可独立解锁（已启用的 SSID 在线即可，蓝牙不要求） */
    public static boolean wifiUnlock(Context c) {
        return sp(c).getBoolean("wifi_unlock", false);
    }

    public static void setWifiUnlock(Context c, boolean v) {
        sp(c).edit().putBoolean("wifi_unlock", v).apply();
    }

    /** SIM 卡 PIN 防呆：默认关闭，开启后 SIM 锁定时拒绝注入 */
    public static boolean simGuard(Context c) {
        return sp(c).getBoolean("sim_guard", false);
    }

    public static void setSimGuard(Context c, boolean v) {
        sp(c).edit().putBoolean("sim_guard", v).apply();
    }

    public static String pin(Context c) {
        return sp(c).getString("pin", "");
    }

    public static void savePin(Context c, String v) {
        sp(c).edit().putString("pin", v).apply();
    }

    public static String rssi(Context c) {
        return sp(c).getString("rssi", "0");
    }

    public static void saveRssi(Context c, String v) {
        sp(c).edit().putString("rssi", v).apply();
    }

    public static boolean wantRunning(Context c) {
        return sp(c).getBoolean("want_running", true);
    }

    public static void setWantRunning(Context c, boolean v) {
        sp(c).edit().putBoolean("want_running", v).apply();
    }

    /** 界面语言："" = 跟随系统，zh / en = 强制 */
    public static String lang(Context c) {
        return sp(c).getString("lang", "");
    }

    public static void setLang(Context c, String v) {
        sp(c).edit().putString("lang", v).apply();
    }

    /** 授权方式：shizuku（默认）/ root / adb（内置无线调试） */
    public static String privilegeMode(Context c) {
        String v = sp(c).getString("privilege_mode", "shizuku");
        return "root".equals(v) || "adb".equals(v) ? v : "shizuku";
    }

    public static void setPrivilegeMode(Context c, String v) {
        sp(c).edit().putString("privilege_mode", v).apply();
    }

    /** 点击按键/开关时轻微震动 */
    public static boolean vibrate(Context c) {
        return sp(c).getBoolean("vibrate", true);
    }

    public static void setVibrate(Context c, boolean v) {
        sp(c).edit().putBoolean("vibrate", v).apply();
    }

    /** 音乐锁屏返回：总开关 */
    public static boolean musicReturn(Context c) {
        return sp(c).getBoolean("music_return", false);
    }

    public static void setMusicReturn(Context c, boolean v) {
        sp(c).edit().putBoolean("music_return", v).apply();
    }

    /** 解锁后打开指定应用的主界面（优先于右滑） */
    public static boolean musicOpenApp(Context c) {
        return sp(c).getBoolean("music_open_app", false);
    }

    public static void setMusicOpenApp(Context c, boolean v) {
        sp(c).edit().putBoolean("music_open_app", v).apply();
    }

    public static String musicAppPkg(Context c) {
        return sp(c).getString("music_app_pkg", "").trim();
    }

    public static void setMusicAppPkg(Context c, String v) {
        sp(c).edit().putString("music_app_pkg", v == null ? "" : v.trim()).apply();
    }

    /** 解锁后向右滑动 */
    public static boolean musicSwipe(Context c) {
        return sp(c).getBoolean("music_swipe", false);
    }

    public static void setMusicSwipe(Context c, boolean v) {
        sp(c).edit().putBoolean("music_swipe", v).apply();
    }

    /** 无线调试（内置 ADB）主机与端口；主机默认本机回环 */
    public static String adbHost(Context c) {
        String v = sp(c).getString("adb_host", "127.0.0.1");
        return v == null || v.isEmpty() ? "127.0.0.1" : v;
    }

    public static void setAdbHost(Context c, String v) {
        sp(c).edit().putString("adb_host", v == null ? "" : v.trim()).apply();
    }

    public static int adbPort(Context c) {
        return sp(c).getInt("adb_port", 0);
    }

    public static void setAdbPort(Context c, int v) {
        sp(c).edit().putInt("adb_port", v).apply();
    }

    public static int adbPairPort(Context c) {
        return sp(c).getInt("adb_pair_port", 0);
    }

    public static void setAdbPairPort(Context c, int v) {
        sp(c).edit().putInt("adb_pair_port", v).apply();
    }
}
