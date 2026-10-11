package com.kqcx.bikemap;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * 系统广播触发的提醒补排入口。
 *
 * 设备重启或应用覆盖安装后，系统中原有的精确闹钟会失效；只要本机还保存着
 * 未结束的提醒状态，就在这里重新排一次。补排失败时保留状态，用户下次打开
 * 应用仍可继续走权限检测与恢复流程。
 */
public final class ReminderRescheduleReceiver extends BroadcastReceiver {
    private static final String TAG = "ReminderReschedule";

    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent == null ? null : intent.getAction();
        if (!Intent.ACTION_BOOT_COMPLETED.equals(action)
                && !Intent.ACTION_MY_PACKAGE_REPLACED.equals(action)) {
            return;
        }
        if (!ReminderScheduler.isActive(context)) {
            return;
        }
        boolean scheduled = ReminderScheduler.rescheduleActive(context);
        DiagnosticLog.event(TAG, action + " reschedule result=" + scheduled);
    }
}
