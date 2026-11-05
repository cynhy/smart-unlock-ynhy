package com.watchunlock.app;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** 开机后拉起状态守护；Shizuku 未启动时服务会空转等待，不报错。 */
public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (!Prefs.wantRunning(context)) return;
        try {
            context.startForegroundService(new Intent(context, MonitorService.class));
        } catch (Throwable ignored) {
        }
    }
}
