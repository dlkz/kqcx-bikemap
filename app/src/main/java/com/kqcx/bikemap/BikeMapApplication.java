package com.kqcx.bikemap;

import android.app.Application;

/**
 * 进程入口：装上本机诊断日志与未捕获异常兜底。
 *
 * <p>放在 Application 而不是 Activity，是为了让后台服务、广播接收器里的崩溃同样能留痕。</p>
 */
public final class BikeMapApplication extends Application {
    @Override
    public void onCreate() {
        super.onCreate();
        DiagnosticLog.init(this);
    }
}
