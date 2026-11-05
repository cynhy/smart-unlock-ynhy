package com.watchunlock.app;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 二级页面：守护日志 */
public class LogActivity extends Activity {

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService pool = Executors.newSingleThreadExecutor();

    private TextView tv;
    private ScrollView sv;
    private Switch swAuto;
    private boolean loaded = false;

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            load();
            ui.postDelayed(this, 2500);
        }
    };

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        setTitle(I18n.t("运行日志", "Run log"));

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(0xFFF3F4F6);
        root.setPadding(dp(16), dp(14), dp(16), dp(14));

        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);

        TextView title = new TextView(this);
        title.setText(I18n.t("运行日志", "Run log"));
        title.setTextSize(18);
        title.setTextColor(0xFF111827);
        title.getPaint().setFakeBoldText(true);
        bar.addView(title, new LinearLayout.LayoutParams(0, -2, 1));

        swAuto = new Switch(this);
        swAuto.setText(I18n.t("自动刷新", "Auto-refresh"));
        swAuto.setTextSize(12);
        swAuto.setChecked(true);
        swAuto.setOnCheckedChangeListener((btn, v) -> {
            ui.removeCallbacks(tick);
            if (v) ui.post(tick);
        });
        bar.addView(swAuto);
        root.addView(bar);

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.setPadding(0, dp(6), 0, dp(6));

        Button refresh = textButton(I18n.t("刷新", "Refresh"));
        refresh.setOnClickListener(v -> load());
        Button copy = textButton(I18n.t("复制", "Copy"));
        copy.setOnClickListener(v -> copy());
        Button clear = textButton(I18n.t("清空", "Clear"));
        clear.setTextColor(0xFFDC2626);
        clear.setOnClickListener(v -> {
            pool.execute(() -> {
                Scripts.Report r = Scripts.clearLog();
                ui.post(() -> {
                    Toast.makeText(this, r.ok ? I18n.t("日志已清空", "Log cleared")
                            : I18n.t("清空失败：%s", "Clear failed: %s", r.text), Toast.LENGTH_SHORT).show();
                    load();
                });
            });
        });
        actions.addView(refresh, new LinearLayout.LayoutParams(0, -2, 1));
        actions.addView(copy, new LinearLayout.LayoutParams(0, -2, 1));
        actions.addView(clear, new LinearLayout.LayoutParams(0, -2, 1));
        root.addView(actions);

        sv = new ScrollView(this);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xFF0F172A);
        bg.setCornerRadius(dp(12));
        sv.setBackground(bg);
        sv.setPadding(dp(12), dp(12), dp(12), dp(12));

        tv = new TextView(this);
        tv.setTextSize(12);
        tv.setTextColor(0xFFE5E7EB);
        tv.setTypeface(android.graphics.Typeface.MONOSPACE);
        tv.setTextIsSelectable(true);
        tv.setText(I18n.t("正在读取…", "Loading…"));
        sv.addView(tv);
        root.addView(sv, new LinearLayout.LayoutParams(-1, 0, 1));

        setContentView(root);
        ui.post(tick);
    }

    private void load() {
        pool.execute(() -> {
            final String s = Scripts.logTail(300);
            ui.post(() -> {
                String body = s.isEmpty()
                        ? I18n.t("（暂无日志：可能还没部署，或日志已清空）",
                        "(No logs yet. The daemon may not be deployed, or the log was cleared.)")
                        : localize(s);
                tv.setText(body);
                if (!loaded) {
                    loaded = true;
                    sv.post(() -> sv.fullScroll(ViewGroup.FOCUS_DOWN));
                } else if (swAuto.isChecked()) {
                    sv.post(() -> sv.fullScroll(ViewGroup.FOCUS_DOWN));
                }
            });
        });
    }

    private void copy() {
        try {
            ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            cm.setPrimaryClip(ClipData.newPlainText("wu.log", tv.getText().toString()));
            Toast.makeText(this, I18n.t("已复制", "Copied"), Toast.LENGTH_SHORT).show();
        } catch (Throwable t) {
            Toast.makeText(this, I18n.t("复制失败", "Copy failed"), Toast.LENGTH_SHORT).show();
        }
    }

    private Button textButton(String s) {
        Button b = new Button(this);
        b.setText(s);
        b.setAllCaps(false);
        b.setTextSize(13);
        b.setTextColor(0xFF2563EB);
        b.setBackground(null);
        return b;
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }

    /** 汉语模式把守护日志的机器字段翻译成人话；英文模式保留原文便于反馈 */
    private String localize(String raw) {
        if (I18n.EN) return raw;
        StringBuilder out = new StringBuilder(raw.length() + 64);
        for (String line : raw.split("\n", -1)) {
            String prefix = "";
            String body = line;
            Matcher pm = Pattern.compile("^(\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}) (.*)$").matcher(line);
            if (pm.matches()) {
                prefix = pm.group(1) + " ";
                body = pm.group(2);
            }
            out.append(prefix).append(zh(body)).append('\n');
        }
        return out.toString();
    }

    private String zh(String s) {
        s = rep(s, "timing: lock=(\\d+)ms presence=(\\d+)ms inject=(\\d+)ms",
                "耗时：锁屏检测=$1ms 条件检查=$2ms 注入=$3ms");
        s = rep(s, "result: unlocked \\(total=(\\d+)ms\\)", "结果：已解锁（总耗时=$1ms）");
        s = rep(s, "result: unlocked \\(slow\\)", "结果：已解锁（慢路径）");
        s = rep(s, "result: unlocked \\(late\\)", "结果：已解锁（延迟确认）");
        s = rep(s, "result: unlocked \\(retry\\)", "结果：已解锁（重试）");
        s = rep(s, "result: still locked \\(app retry\\)", "结果：仍未解锁（应用重试）");
        s = rep(s, "result: still locked", "结果：仍未解锁");
        s = rep(s, "present\\(cache\\): (\\S+) (\\d+/\\d+)", "在线（缓存）：$1 $2");
        s = rep(s, "present: (\\S+) (\\d+/\\d+)", "在线：$1 $2");
        s = rep(s, "event: screen on", "事件：屏幕亮起");
        s = rep(s, "trigger: app", "触发：应用亮屏");
        s = rep(s, "trigger: 连上可信 WiFi (.+)", "触发：连上可信 WiFi $1");
        s = rep(s, "trigger: start", "触发：启动时");
        s = rep(s, "trigger: screen", "触发：亮屏事件");
        s = rep(s, "trigger: wifi", "触发：WiFi 事件");
        s = rep(s, "trigger: (\\S+)", "触发：$1");
        s = rep(s, "skip: already unlocked", "跳过：已解锁");
        s = rep(s, "skip: display off", "跳过：屏幕已熄灭");
        s = rep(s, "skip: not on keyguard \\((.+)\\)", "跳过：不在锁屏界面（$1）");
        s = rep(s, "skip: sim locked \\((.+)\\), refuse to inject", "跳过：SIM 卡已锁定（$1），拒绝注入");
        s = rep(s, "skip: recent injection", "跳过：刚注入过，防重复");
        s = rep(s, "skip: another attempt running", "跳过：已有解锁流程在进行");
        s = rep(s, "slow: keyguard still up, try open \\+ verify", "慢路径：锁屏仍在，尝试唤醒并复验");
        s = rep(s, "retry: keypad not ready, alternate path", "重试：PIN 键盘未就绪，改用另一种打开方式");
        s = rep(s, "retry: keypad gone, re-check", "重试：键盘消失，重新检查");
        s = rep(s, "retry: clear and inject again \\(del=(\\d+)\\)", "重试：清空后重新注入（删除 $1 个字符）");
        s = rep(s, "abort: no keypad, refuse to inject", "中止：没有 PIN 键盘，拒绝注入");
        s = rep(s, "too many failures, cooling down (\\d+)s \\(cycle (\\d+)\\)",
                "失败过多，进入冷却 $1 秒（第 $2 轮）");
        s = rep(s, "paused (\\d+)s \\(until (\\d+)\\)", "已暂停 $1 秒（至 $2）");
        s = rep(s, "paused \\(until (\\d+)\\), skip startup unlock", "已暂停（至 $1），跳过启动时解锁");
        s = rep(s, "paused, skip \\(left (\\d+)s\\)", "已暂停，跳过（剩余 $1 秒）");
        s = rep(s, "cooling down \\(until (\\d+)\\), skip startup unlock", "冷却中（至 $1），跳过启动时解锁");
        s = rep(s, "cooling down, skip \\(left (\\d+)s\\)", "冷却中，跳过（剩余 $1 秒）");
        s = rep(s, "resume", "已恢复");
        s = rep(s, "daemon started \\(pid (\\d+)\\) status=(\\w+) mode=(\\w+) rssi_min=(\\d+) "
                        + "wifi_unlock=(\\d+) wifi_required=(\\d+) sim_guard=(\\d+)",
                "守护已启动（PID $1）状态=$2 模式=$3 RSSI 门槛=$4 WiFi 独立=$5 WiFi 必要=$6 SIM 防呆=$7");
        s = rep(s, "daemon started \\(pid (\\d+)\\) status=(\\w+) mode=(\\w+) rssi_min=(\\d+) wifi=(\\S+)",
                "守护已启动（PID $1）状态=$2 模式=$3 RSSI 门槛=$4 WiFi=$5");
        s = rep(s, "already running \\(pid (\\d+)(?:, ps)?\\)(?:, exit)?", "已有守护在运行（PID $1），退出");
        s = rep(s, "already running \\(lockdir\\)(?:, exit)?", "已有守护在运行（目录锁命中），退出");
        s = rep(s, "state=disabled, refuse to start；?(.+)", "状态=已停用，拒绝启动$1");
        s = rep(s, "state=disabled, stop daemon", "状态=已停用，守护停止");
        s = rep(s, "logcat exited, restart in (\\d+)s", "事件监听退出，$1 秒后重启");
        s = rep(s, "logcat exited, restarting in (\\d+)s", "事件监听退出，$1 秒后重启");
        s = rep(s, "FATAL: ", "严重：");
        s = rep(s, "mkdir: ", "创建目录：");
        s = rep(s, "scripts: ", "写入脚本：");
        s = rep(s, "stop: ", "停止旧守护：");
        s = rep(s, "clean: ", "清理：");
        s = rep(s, "start: ", "启动：");
        s = rep(s, "kill: ", "结束进程：");
        s = rep(s, "rm: ", "删除：");
        return s;
    }

    private static String rep(String s, String re, String to) {
        try {
            return s.replaceAll(re, to);
        } catch (Throwable t) {
            return s;
        }
    }

    @Override
    protected void onDestroy() {
        ui.removeCallbacks(tick);
        pool.shutdownNow();
        super.onDestroy();
    }
}
