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
        return SecurePin.load(c);
    }

    public static void savePin(Context c, String v) {
        SecurePin.save(c, v);
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

    /** 已添加的候选应用，换行分隔；与 music_app_pkg 的选中项分开保存 */
    public static String musicApps(Context c) {
        String v = sp(c).getString("music_apps", "");
        return v == null ? "" : v;
    }

    public static void setMusicApps(Context c, String v) {
        sp(c).edit().putString("music_apps", v == null ? "" : v).apply();
    }

    public static void addMusicApp(Context c, String pkg) {
        String p = pkg == null ? "" : pkg.trim();
        if (!p.matches("[A-Za-z0-9._]{1,200}")) return;
        for (String s : musicApps(c).split("\n")) {
            if (p.equals(s.trim())) return;
        }
        String cur = musicApps(c).trim();
        setMusicApps(c, cur.isEmpty() ? p : cur + "\n" + p);
    }

    public static void removeMusicApp(Context c, String pkg) {
        String p = pkg == null ? "" : pkg.trim();
        StringBuilder sb = new StringBuilder();
        for (String s : musicApps(c).split("\n")) {
            String t = s.trim();
            if (t.isEmpty() || t.equals(p)) continue;
            if (sb.length() > 0) sb.append('\n');
            sb.append(t);
        }
        setMusicApps(c, sb.toString());
        if (p.equals(musicAppPkg(c))) setMusicAppPkg(c, "");
    }

    /** 亮屏时系统报告未锁屏（例如刚解锁或受信任设备生效），跳过本次注入 */
    public static boolean skipWhenUnlocked(Context c) {
        return sp(c).getBoolean("skip_when_unlocked", true);
    }

    public static void setSkipWhenUnlocked(Context c, boolean v) {
        sp(c).edit().putBoolean("skip_when_unlocked", v).apply();
    }

    /** 无线调试配对成功后，尝试用内置 ADB 执行 Shizuku 的官方 start.sh */
    public static boolean shizukuAutoStart(Context c) {
        return sp(c).getBoolean("shizuku_auto_start", false);
    }

    public static void setShizukuAutoStart(Context c, boolean v) {
        sp(c).edit().putBoolean("shizuku_auto_start", v).apply();
    }

    // ---------------------------------------------------------------- 增强防护

    /** 增强防护总开关 */
    public static boolean protectEnabled(Context c) {
        return sp(c).getBoolean("protect_enabled", false);
    }

    public static void setProtectEnabled(Context c, boolean v) {
        sp(c).edit().putBoolean("protect_enabled", v).apply();
    }

    /** 有 MAC 时禁止按名称回退（MAC 不匹配即视为不在线） */
    public static boolean macStrict(Context c) {
        return sp(c).getBoolean("mac_strict", true);
    }

    public static void setMacStrict(Context c, boolean v) {
        sp(c).edit().putBoolean("mac_strict", v).apply();
    }

    /** MAC 不匹配时，允许在已匹配设备里找同名设备来解锁（身份不可靠） */
    public static boolean macNameFallback(Context c) {
        return sp(c).getBoolean("mac_name_fallback", false);
    }

    public static void setMacNameFallback(Context c, boolean v) {
        sp(c).edit().putBoolean("mac_name_fallback", v).apply();
    }

    /** WiFi 绑定 BSSID，并要求非开放网络 */
    public static boolean wifiBindBssid(Context c) {
        return sp(c).getBoolean("wifi_bind_bssid", true);
    }

    public static void setWifiBindBssid(Context c, boolean v) {
        sp(c).edit().putBoolean("wifi_bind_bssid", v).apply();
    }

    /** 允许开放（无密码）网络也满足 WiFi 条件 */
    public static boolean wifiAllowOpen(Context c) {
        return sp(c).getBoolean("wifi_allow_open", false);
    }

    public static void setWifiAllowOpen(Context c, boolean v) {
        sp(c).edit().putBoolean("wifi_allow_open", v).apply();
    }

    /**
     * 延长解锁（实验性）：在身边时临时关闭锁屏密码，断开后立即写回。
     * 默认关闭；开启需要连续多级确认，且必须先在应用内校验系统 PIN。
     */
    public static boolean extendUnlock(Context c) {
        return sp(c).getBoolean("extend_unlock", false);
    }

    public static void setExtendUnlock(Context c, boolean v) {
        sp(c).edit().putBoolean("extend_unlock", v).apply();
    }

    /**
     * 每次亮屏都现场查询设备状态（默认关闭）。
     * 关闭时用蓝牙事件缓存，解锁更快；打开后每次多约 200~350ms，但缓存可能过期时更稳。
     */
    public static boolean presenceLive(Context c) {
        return sp(c).getBoolean("presence_live", false);
    }

    public static void setPresenceLive(Context c, boolean v) {
        sp(c).edit().putBoolean("presence_live", v).apply();
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
