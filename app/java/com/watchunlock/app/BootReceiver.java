package com.watchunlock.app;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;

/** 开机后拉起状态守护；权限缺失时发一条可点击的通知，引导用户授权。 */
public class BootReceiver extends BroadcastReceiver {

    private static final String CH_PERM = "perm_alert";
    private static final int ID_PERM = 9001;

    @Override
    public void onReceive(Context context, Intent intent) {
        if (!Prefs.wantRunning(context)) return;
        try {
            context.startForegroundService(new Intent(context, MonitorService.class));
        } catch (Throwable ignored) {
        }
        // 开机时普通应用不能直接弹权限框，先发通知；用户点开后再由 MainActivity 请求
        if (Build.VERSION.SDK_INT >= 31
                && context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)
                != PackageManager.PERMISSION_GRANTED) {
            notifyPermission(context);
        }
    }

    private void notifyPermission(Context c) {
        try {
            NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm == null) return;
            if (Build.VERSION.SDK_INT >= 26) {
                NotificationChannel ch = new NotificationChannel(CH_PERM,
                        I18n.t("权限提醒", "Permission reminder"),
                        NotificationManager.IMPORTANCE_HIGH);
                ch.setDescription(I18n.t("蓝牙权限缺失时提醒授权",
                        "Remind to grant Bluetooth permission"));
                nm.createNotificationChannel(ch);
            }
            Intent i = new Intent(c, MainActivity.class);
            i.putExtra("request_permissions", true);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            PendingIntent pi = PendingIntent.getActivity(c, 0, i,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            Notification.Builder b = Build.VERSION.SDK_INT >= 26
                    ? new Notification.Builder(c, CH_PERM)
                    : new Notification.Builder(c);
            b.setSmallIcon(R.drawable.ic_launcher)
                    .setContentTitle(I18n.t("需要「附近的设备」权限",
                            "Nearby devices permission required"))
                    .setContentText(I18n.t("蓝牙权限被关闭，手表解锁无法工作。点此去授权。",
                            "Bluetooth permission is off, so watch unlocking cannot work. Tap to grant it."))
                    .setContentIntent(pi)
                    .setAutoCancel(true)
                    .setPriority(Notification.PRIORITY_HIGH);
            nm.notify(ID_PERM, b.build());
        } catch (Throwable ignored) {
        }
    }
}
