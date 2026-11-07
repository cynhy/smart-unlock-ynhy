package com.watchunlock.app;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

/** 通知点开后直接输入 6 位配对码，不再跳回无线调试页面。 */
public class PairCodeActivity extends Activity {

    private EditText code;
    private TextView status;
    private boolean receiverOn = false;

    private final BroadcastReceiver stateReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent i) {
            int st = i.getIntExtra(AdbPairingService.EXTRA_STATE, 0);
            String msg = i.getStringExtra(AdbPairingService.EXTRA_MESSAGE);
            if (status != null && msg != null) status.setText(msg);
            if (st == AdbPairingService.STATE_CONNECTED) {
                Toast.makeText(PairCodeActivity.this,
                        I18n.t("配对成功，已连接", "Paired and connected"), Toast.LENGTH_SHORT).show();
                finish();
            } else if (st == AdbPairingService.STATE_FAILED) {
                if (code != null) code.setEnabled(true);
            } else if (st == AdbPairingService.STATE_FOUND && code != null) {
                code.setEnabled(true);
            }
        }
    };

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        I18n.init(this);
        setTitle(I18n.t("输入配对码", "Enter pairing code"));

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(0xFFF3F4F6);
        root.setPadding(dp(18), dp(18), dp(18), dp(18));

        TextView title = new TextView(this);
        title.setText(I18n.t("输入无线调试配对码", "Enter the wireless debugging pairing code"));
        title.setTextSize(19);
        title.setTextColor(0xFF111827);
        title.getPaint().setFakeBoldText(true);
        root.addView(title);

        TextView hint = new TextView(this);
        hint.setText(I18n.t(
                "请在系统「无线调试 → 使用配对码配对设备」弹窗中查看 6 位数字。"
                        + "保持那个弹窗打开，输入后点「发送」。配对成功后会自动连接，不需要再回到无线调试页面。",
                "Look at the 6-digit number in the system “Pair device with pairing code” dialog. "
                        + "Keep it open, enter the code and tap Send. The app connects automatically after pairing."));
        hint.setTextSize(12);
        hint.setTextColor(0xFF6B7280);
        hint.setPadding(0, dp(8), 0, dp(10));
        root.addView(hint);

        code = new EditText(this);
        code.setHint(I18n.t("6 位配对码", "6-digit code"));
        code.setInputType(InputType.TYPE_CLASS_NUMBER);
        code.setTextSize(22);
        code.setGravity(Gravity.CENTER);
        code.setSingleLine(true);
        code.setPadding(dp(10), dp(10), dp(10), dp(10));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xFFFFFFFF);
        bg.setCornerRadius(dp(10));
        code.setBackground(bg);
        root.addView(code, new LinearLayout.LayoutParams(-1, -2));

        Button send = new Button(this);
        send.setText(I18n.t("发送配对码", "Send code"));
        send.setTextSize(16);
        send.setOnClickListener(v -> send());
        root.addView(send, gap(dp(12), -1, dp(48)));

        Button retry = new Button(this);
        retry.setText(I18n.t("重新搜索配对服务", "Search again"));
        retry.setTextSize(14);
        retry.setOnClickListener(v -> {
            Intent i = new Intent(this, AdbPairingService.class);
            i.setAction(AdbPairingService.ACTION_START);
            startForegroundService(i);
            if (status != null) status.setText(I18n.t("正在搜索…", "Searching…"));
        });
        root.addView(retry, gap(dp(8), -1, dp(44)));

        status = new TextView(this);
        status.setTextSize(13);
        status.setTextColor(0xFF2563EB);
        status.setPadding(0, dp(12), 0, 0);
        status.setText(I18n.t("等待输入…", "Waiting for the code…"));
        root.addView(status);

        setContentView(root);
    }

    @Override
    protected void onResume() {
        super.onResume();
        IntentFilter f = new IntentFilter(AdbPairingService.ACTION_STATE);
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(stateReceiver, f, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(stateReceiver, f);
        }
        receiverOn = true;
        int p = Prefs.adbPairPort(this);
        if (p <= 0 && status != null) {
            status.setText(I18n.t("还没找到配对端口，请点「重新搜索配对服务」",
                    "Pairing port not found yet. Tap “Search again”"));
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (receiverOn) {
            try {
                unregisterReceiver(stateReceiver);
            } catch (Throwable ignored) {
            }
            receiverOn = false;
        }
    }

    private void send() {
        String c = code.getText().toString().trim();
        if (c.length() != 6) {
            Toast.makeText(this, I18n.t("配对码必须是 6 位数字",
                    "The pairing code must be 6 digits"), Toast.LENGTH_SHORT).show();
            return;
        }
        int port = Prefs.adbPairPort(this);
        if (port <= 0) {
            Toast.makeText(this, I18n.t("还没找到配对端口，请保持配对弹窗打开后点「重新搜索」",
                    "Pairing port not found. Keep the pairing dialog open and tap Search again"),
                    Toast.LENGTH_LONG).show();
            return;
        }
        code.setEnabled(false);
        status.setText(I18n.t("正在配对…", "Pairing…"));
        Intent i = new Intent(this, AdbPairingService.class);
        i.setAction(AdbPairingService.ACTION_REPLY);
        i.putExtra(AdbPairingService.EXTRA_CODE, c);
        i.putExtra(AdbPairingService.EXTRA_PORT, port);
        startForegroundService(i);
    }

    private LinearLayout.LayoutParams gap(int top, int w, int h) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(w, h);
        lp.topMargin = top;
        return lp;
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }
}
