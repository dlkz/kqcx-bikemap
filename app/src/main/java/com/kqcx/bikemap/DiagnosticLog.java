package com.kqcx.bikemap;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.util.Log;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FilenameFilter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.io.Reader;
import java.io.StringWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * 本机诊断日志：事件骨架常开，详细内容按需打开，崩溃单独留痕。
 *
 * <p>口径：</p>
 * <ul>
 *   <li>只写本机 {@code filesDir/diagnose/}，不联网、不上传；导出由用户在「关于」页
 *       主动触发，走系统分享，应用不经手网络；</li>
 *   <li>不记录坐标原值、完整车辆编号、凭据或任何用户输入内容；</li>
 *   <li>事件日志按大小滚动（两个文件封顶），崩溃文件保留最近若干份；</li>
 *   <li>写入同步完成——崩溃前最后几条不能丢，而那恰恰是最有价值的一段。</li>
 * </ul>
 */
final class DiagnosticLog {
    private static final String ANDROID_TAG = "BikeMap";
    private static final String DIR_NAME = "diagnose";
    private static final String EVENT_FILE = "event.log";
    private static final String EVENT_BACKUP = "event.log.1";
    private static final String CRASH_PREFIX = "crash-";
    private static final String EXPORT_DIR = "diagnose-export";
    private static final int MAX_CRASH_FILES = 5;
    private static final int MAX_EXPORT_FILES = 3;
    private static final long MAX_EVENT_BYTES = 256L * 1024L;
    private static final String PREF_DETAIL = "diagnostic_detail";
    private static final String LINE_TIME_FORMAT = "MM-dd HH:mm:ss.SSS";
    /**
     * 崩溃文件名用**毫秒**精度：秒精度下同秒崩两次会同名互覆——崩溃循环里这很容易发生
     * （参考工程踩过同一个坑）。
     */
    private static final String CRASH_TIME_FORMAT = "yyyyMMdd-HHmmss.SSS";
    private static final String EXPORT_TIME_FORMAT = "yyyyMMdd-HHmmss";

    private static final Object LOCK = new Object();
    private static final SimpleDateFormat LINE_TIME =
            new SimpleDateFormat(LINE_TIME_FORMAT, Locale.ROOT);
    private static final SimpleDateFormat CRASH_TIME =
            new SimpleDateFormat(CRASH_TIME_FORMAT, Locale.ROOT);
    private static final SimpleDateFormat EXPORT_TIME =
            new SimpleDateFormat(EXPORT_TIME_FORMAT, Locale.ROOT);

    private static Context appContext;
    private static File logDir;
    private static boolean detailEnabled;
    private static boolean crashHandlerInstalled;

    private DiagnosticLog() {
    }

    /** 应用启动时调用一次；同时装上未捕获异常兜底。 */
    static void init(Context context) {
        Context app = context.getApplicationContext();
        synchronized (LOCK) {
            if (appContext != null) {
                return;
            }
            appContext = app;
            logDir = new File(app.getFilesDir(), DIR_NAME);
            if (!logDir.exists() && !logDir.mkdirs()) {
                Log.w(ANDROID_TAG, "diagnostic log directory unavailable");
            }
            detailEnabled = prefs(app).getBoolean(PREF_DETAIL, false);
        }
        installCrashHandler();
        event("App", "start " + BuildConfig.VERSION_NAME
                + " (" + BuildConfig.BUILD_NUMBER + ")");
    }

    /** 事件骨架：始终记录，内容不含任何用户数据。 */
    static void event(String tag, String message) {
        write("I", tag, message, null);
    }

    /** 详细日志：只有用户在「关于」页打开开关后才记录。 */
    static void detail(String tag, String message) {
        if (!detailEnabled) {
            return;
        }
        write("D", tag, message, null);
    }

    static void warn(String tag, String message) {
        write("W", tag, message, null);
    }

    static void warn(String tag, String message, Throwable throwable) {
        write("W", tag, message, throwable);
    }

    static void error(String tag, String message, Throwable throwable) {
        write("E", tag, message, throwable);
    }

    static boolean isDetailEnabled() {
        return detailEnabled;
    }

    /**
     * 删除应用自己保存的诊断记录；只处理已知日志文件和导出报告，不删除目录或用户数据。
     */
    static boolean clearAll(Context context) {
        Context app = context.getApplicationContext();
        synchronized (LOCK) {
            File dir = logDir != null ? logDir : new File(app.getFilesDir(), DIR_NAME);
            boolean logsDeleted = deleteFiles(dir, (ignored, name) ->
                    EVENT_FILE.equals(name)
                            || EVENT_BACKUP.equals(name)
                            || name.startsWith(CRASH_PREFIX));
            File exportDir = new File(app.getCacheDir(), EXPORT_DIR);
            boolean exportsDeleted = deleteFiles(exportDir, (ignored, name) ->
                    name.startsWith("bikemap-log-") && name.endsWith(".txt"));
            return logsDeleted && exportsDeleted;
        }
    }

    static void setDetailEnabled(Context context, boolean enabled) {
        detailEnabled = enabled;
        prefs(context.getApplicationContext())
                .edit()
                .putBoolean(PREF_DETAIL, enabled)
                .apply();
        event("App", "detail logging " + (enabled ? "on" : "off"));
    }

    /** 「关于」页那一行的副标题：让用户知道导出里有什么。 */
    static String summary() {
        int crashes = crashFiles().size();
        long eventBytes = 0L;
        synchronized (LOCK) {
            if (logDir != null) {
                eventBytes = new File(logDir, EVENT_FILE).length()
                        + new File(logDir, EVENT_BACKUP).length();
            }
        }
        if (crashes == 0 && eventBytes == 0L) {
            return "暂无记录，遇到问题后再导出";
        }
        StringBuilder summary = new StringBuilder();
        if (crashes > 0) {
            summary.append("崩溃 ").append(crashes).append(" 份");
        }
        if (eventBytes > 0L) {
            if (summary.length() > 0) {
                summary.append(" · ");
            }
            summary.append("事件日志 ").append(Math.max(1L, eventBytes / 1024L)).append(" KB");
        }
        return summary.append(" · 经系统分享发送").toString();
    }

    private static boolean deleteFiles(File dir, FilenameFilter filter) {
        File[] files = dir.listFiles(filter);
        if (files == null) {
            return !dir.exists();
        }
        boolean deleted = true;
        for (File file : files) {
            if (file.isFile() && !file.delete()) {
                deleted = false;
            }
        }
        return deleted;
    }

    /**
     * 把事件日志与崩溃记录合成一份报告写到缓存目录，返回该文件；调用方负责用
     * FileProvider 分享。缓存目录随系统回收，不留长期副本。
     */
    static File exportReport(Context context) throws IOException {
        Context app = context.getApplicationContext();
        StringBuilder report = new StringBuilder();
        appendHeader(report, app);
        appendSection(report, "事件日志（较早）", new File(logDir, EVENT_BACKUP));
        appendSection(report, "事件日志", new File(logDir, EVENT_FILE));
        List<File> crashes = crashFiles();
        for (File crash : crashes) {
            appendSection(report, "崩溃记录 " + crash.getName(), crash);
        }
        if (crashes.isEmpty()) {
            report.append("\n===== 崩溃记录 =====\n（无）\n");
        }

        File exportDir = new File(app.getCacheDir(), EXPORT_DIR);
        if (!exportDir.exists() && !exportDir.mkdirs()) {
            throw new IOException("export directory unavailable");
        }
        String stamp;
        synchronized (LOCK) {
            stamp = EXPORT_TIME.format(new Date());
        }
        File target = new File(exportDir, "bikemap-log-" + stamp + ".txt");
        try (Writer writer = new OutputStreamWriter(
                new FileOutputStream(target, false),
                StandardCharsets.UTF_8
        )) {
            writer.write(report.toString());
        }
        pruneExports(exportDir, target);
        return target;
    }

    private static void appendHeader(StringBuilder report, Context app) {
        report.append("BikeMap 诊断日志\n");
        report.append("导出时间: ").append(new SimpleDateFormat(
                "yyyy-MM-dd HH:mm:ss", Locale.ROOT).format(new Date())).append('\n');
        report.append("应用版本: ").append(BuildConfig.VERSION_NAME)
                .append(" (build ").append(BuildConfig.BUILD_NUMBER).append(", ")
                .append(BuildConfig.DEBUG ? "debug" : "release").append(")\n");
        report.append("包名: ").append(app.getPackageName()).append('\n');
        report.append("Android: ").append(Build.VERSION.RELEASE)
                .append(" (API ").append(Build.VERSION.SDK_INT).append(")\n");
        report.append("机型: ").append(Build.MANUFACTURER).append(' ')
                .append(Build.MODEL).append('\n');
        report.append("详细日志: ").append(detailEnabled ? "开启" : "关闭").append('\n');
        report.append("提醒计时: ")
                .append(ReminderScheduler.isActive(app) ? "运行中" : "未运行").append('\n');
        report.append("车辆预警: ")
                .append(VehicleAlertService.isTimerRunning(app) ? "运行中" : "未启动").append('\n');
        report.append("位置权限: ").append(hasPermission(app,
                android.Manifest.permission.ACCESS_FINE_LOCATION) ? "已授予" : "未授予").append('\n');
        report.append("通知权限: ").append(VehicleAlertService.hasNotificationAccess(app)
                ? "可用" : "不可用").append('\n');
        report.append('\n');
    }

    private static boolean hasPermission(Context context, String permission) {
        return context.checkSelfPermission(permission)
                == android.content.pm.PackageManager.PERMISSION_GRANTED;
    }

    private static void appendSection(StringBuilder report, String title, File file) {
        if (file == null || !file.isFile()) {
            return;
        }
        report.append("===== ").append(title)
                .append(" (").append(file.length()).append(" B) =====\n");
        report.append(readAll(file));
        report.append('\n');
    }

    private static String readAll(File file) {
        StringBuilder content = new StringBuilder();
        try (Reader reader = new InputStreamReader(
                new FileInputStream(file),
                StandardCharsets.UTF_8
        )) {
            char[] buffer = new char[4096];
            int read;
            while ((read = reader.read(buffer)) > 0) {
                content.append(buffer, 0, read);
            }
        } catch (IOException exception) {
            content.append("（读取失败：").append(exception.getMessage()).append("）");
        }
        return content.toString();
    }

    private static void installCrashHandler() {
        synchronized (LOCK) {
            if (crashHandlerInstalled) {
                return;
            }
            crashHandlerInstalled = true;
        }
        Thread.UncaughtExceptionHandler previous =
                Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((thread, throwable) -> {
            try {
                writeCrash(thread, throwable);
            } catch (Throwable ignored) {
                // 落盘失败绝不改变原有的崩溃流程。
            }
            if (previous != null) {
                previous.uncaughtException(thread, throwable);
            }
        });
    }

    private static void write(String level, String tag, String message, Throwable throwable) {
        if (appContext == null) {
            Log.println(levelToPriority(level), ANDROID_TAG, tag + ": " + message);
            return;
        }
        // 仍然同步到 logcat，方便 adb 现场调试。
        Log.println(levelToPriority(level), ANDROID_TAG, tag + ": " + message);
        synchronized (LOCK) {
            if (logDir == null) {
                return;
            }
            StringBuilder line = new StringBuilder();
            File current = new File(logDir, EVENT_FILE);
            if (current.length() > MAX_EVENT_BYTES) {
                rollEventLog(current);
            }
            line.append(LINE_TIME.format(new Date()))
                    .append(' ').append(level).append(' ')
                    .append(tag).append(": ").append(message);
            if (throwable != null) {
                line.append('\n').append(stackTraceOf(throwable));
            }
            appendText(
                    new File(logDir, EVENT_FILE),
                    line.append('\n').toString()
            );
        }
    }

    private static int levelToPriority(String level) {
        if ("E".equals(level)) {
            return Log.ERROR;
        }
        if ("W".equals(level)) {
            return Log.WARN;
        }
        if ("D".equals(level)) {
            return Log.DEBUG;
        }
        return Log.INFO;
    }

    /** 滚动事件日志：调用方已持有 {@link #LOCK}。 */
    private static void rollEventLog(File current) {
        File backup = new File(logDir, EVENT_BACKUP);
        if (backup.exists() && !backup.delete()) {
            Log.w(ANDROID_TAG, "could not rotate event log");
        }
        if (!current.renameTo(backup)) {
            Log.w(ANDROID_TAG, "could not rotate event log");
        }
    }

    private static void appendText(File file, String text) {
        try (Writer writer = new OutputStreamWriter(
                new FileOutputStream(file, true),
                StandardCharsets.UTF_8
        )) {
            writer.write(text);
        } catch (IOException ignored) {
            // 诊断日志写失败不能影响主流程。
        }
    }

    private static void writeCrash(Thread thread, Throwable throwable) {
        synchronized (LOCK) {
            if (logDir == null) {
                return;
            }
            if (!logDir.exists() && !logDir.mkdirs()) {
                return;
            }
            String stamp = CRASH_TIME.format(new Date());
            File file = new File(logDir, CRASH_PREFIX + stamp + ".txt");
            StringBuilder content = new StringBuilder();
            content.append("time: ").append(new Date()).append('\n');
            content.append("thread: ").append(thread.getName()).append('\n');
            content.append("app: ").append(BuildConfig.VERSION_NAME)
                    .append(" (").append(BuildConfig.BUILD_NUMBER).append(")\n");
            content.append("android: ").append(Build.VERSION.RELEASE)
                    .append(" (API ").append(Build.VERSION.SDK_INT).append(")\n");
            content.append("device: ").append(Build.MANUFACTURER)
                    .append(' ').append(Build.MODEL).append("\n\n");
            content.append(stackTraceOf(throwable));
            try (Writer writer = new OutputStreamWriter(
                    new FileOutputStream(file, false),
                    StandardCharsets.UTF_8
            )) {
                writer.write(content.toString());
            } catch (IOException ignored) {
                return;
            }
            pruneCrashFiles(file);
        }
    }

    private static String stackTraceOf(Throwable throwable) {
        StringWriter buffer = new StringWriter();
        try (PrintWriter printer = new PrintWriter(buffer)) {
            throwable.printStackTrace(printer);
        }
        return buffer.toString();
    }

    private static List<File> crashFiles() {
        File dir;
        synchronized (LOCK) {
            dir = logDir;
        }
        if (dir == null) {
            return new ArrayList<>();
        }
        File[] files = dir.listFiles((ignored, name) -> name.startsWith(CRASH_PREFIX));
        if (files == null) {
            return new ArrayList<>();
        }
        List<File> sorted = new ArrayList<>(Arrays.asList(files));
        sorted.sort(Comparator.comparing(File::getName).reversed());
        return sorted;
    }

    /**
     * 保留最近 {@link #MAX_CRASH_FILES} 份；清理时排除刚写的那份，否则高频崩溃下
     * 可能把最新一份砍掉。
     */
    private static void pruneCrashFiles(File justWritten) {
        List<File> files = crashFiles();
        files.remove(justWritten);
        for (int index = MAX_CRASH_FILES - 1; index < files.size(); index++) {
            files.get(index).delete();
        }
    }

    private static void pruneExports(File exportDir, File justWritten) {
        File[] files = exportDir.listFiles();
        if (files == null) {
            return;
        }
        List<File> sorted = new ArrayList<>(Arrays.asList(files));
        sorted.remove(justWritten);
        sorted.sort(Comparator.comparingLong(File::lastModified).reversed());
        for (int index = MAX_EXPORT_FILES - 1; index < sorted.size(); index++) {
            sorted.get(index).delete();
        }
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(
                BaseActivity.PREFS_APP,
                Context.MODE_PRIVATE
        );
    }
}
