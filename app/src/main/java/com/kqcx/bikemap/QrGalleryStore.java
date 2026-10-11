package com.kqcx.bikemap;

import android.content.ContentValues;
import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/** Owns the temporary QR files written to the system gallery. */
final class QrGalleryStore {
    static final long AUTO_DELETE_MS = 30_000L;

    private static final String PREF_PENDING_QR_DELETES = "pending_qr_deletes";
    private static final String PENDING_QR_PREFIX = "bike_qr_";
    private static final String QR_RELATIVE_PATH = "Pictures/BikeMap/";

    private final Context context;
    private final String preferencesName;

    QrGalleryStore(Context context, String preferencesName) {
        this.context = context.getApplicationContext();
        this.preferencesName = preferencesName;
    }

    SavedQr save(Bitmap bitmap, String number) {
        String token = UUID.randomUUID().toString().replace("-", "");
        String fileName = PENDING_QR_PREFIX + number + "_" + token + ".png";
        Uri savedUri = null;
        String savedPath = "";
        try {
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, buffer)) {
                throw new IOException("无法编码二维码图片");
            }
            byte[] pngBytes = buffer.toByteArray();
            String sha256 = sha256(pngBytes);

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ContentValues values = new ContentValues();
                values.put(MediaStore.Images.Media.DISPLAY_NAME, fileName);
                values.put(MediaStore.Images.Media.MIME_TYPE, "image/png");
                values.put(MediaStore.Images.Media.RELATIVE_PATH, QR_RELATIVE_PATH);
                values.put(MediaStore.Images.Media.SIZE, pngBytes.length);
                values.put(MediaStore.Images.Media.IS_PENDING, 1);
                savedUri = context.getContentResolver().insert(
                        MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                        values
                );
                if (savedUri == null) {
                    throw new IOException("无法创建相册文件");
                }
                try (OutputStream output = context.getContentResolver().openOutputStream(savedUri)) {
                    if (output == null) {
                        throw new IOException("无法写入二维码图片");
                    }
                    output.write(pngBytes);
                }
                values.clear();
                values.put(MediaStore.Images.Media.IS_PENDING, 0);
                if (context.getContentResolver().update(savedUri, values, null, null) <= 0) {
                    throw new IOException("无法完成二维码图片");
                }
            } else {
                File directory = new File(
                        Environment.getExternalStoragePublicDirectory(
                                Environment.DIRECTORY_PICTURES
                        ),
                        "BikeMap"
                );
                if (!directory.exists() && !directory.mkdirs()) {
                    throw new IOException("无法创建相册目录");
                }
                File outputFile = new File(directory, fileName).getCanonicalFile();
                savedPath = outputFile.getAbsolutePath();
                ContentValues values = new ContentValues();
                values.put(MediaStore.Images.Media.DISPLAY_NAME, fileName);
                values.put(MediaStore.Images.Media.MIME_TYPE, "image/png");
                values.put(MediaStore.Images.Media.DATA, savedPath);
                values.put(MediaStore.Images.Media.SIZE, pngBytes.length);
                savedUri = context.getContentResolver().insert(
                        MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                        values
                );
                if (savedUri == null) {
                    throw new IOException("无法创建相册记录");
                }
                try (FileOutputStream output = new FileOutputStream(outputFile)) {
                    output.write(pngBytes);
                }
            }

            return new SavedQr(
                    System.currentTimeMillis() + AUTO_DELETE_MS,
                    savedUri,
                    fileName,
                    pngBytes.length,
                    sha256,
                    savedPath
            );
        } catch (Exception exception) {
            if (savedUri != null) {
                try {
                    context.getContentResolver().delete(savedUri, null, null);
                } catch (Exception ignored) {
                    // Best-effort cleanup of the exact row created above.
                }
            }
            if (!savedPath.isEmpty()) {
                try {
                    new File(savedPath).delete();
                } catch (Exception ignored) {
                    // Best-effort cleanup of the exact path created above.
                }
            }
            return null;
        }
    }

    void remember(SavedQr pending) {
        SharedPreferences preferences = preferences();
        Set<String> stored = new HashSet<>(
                preferences.getStringSet(PREF_PENDING_QR_DELETES, new HashSet<>())
        );
        stored.add(pending.serialize());
        preferences.edit().putStringSet(PREF_PENDING_QR_DELETES, stored).apply();
    }

    List<SavedQr> loadPending() {
        SharedPreferences preferences = preferences();
        Set<String> stored = preferences.getStringSet(PREF_PENDING_QR_DELETES, null);
        List<SavedQr> pendingItems = new ArrayList<>();
        if (stored == null || stored.isEmpty()) {
            return pendingItems;
        }

        Set<String> invalid = new HashSet<>();
        for (String entry : new HashSet<>(stored)) {
            try {
                SavedQr pending = parsePendingQr(entry);
                if (pending == null) {
                    invalid.add(entry);
                } else {
                    pendingItems.add(pending);
                }
            } catch (Exception exception) {
                invalid.add(entry);
            }
        }
        if (!invalid.isEmpty()) {
            Set<String> updated = new HashSet<>(stored);
            updated.removeAll(invalid);
            preferences.edit().putStringSet(PREF_PENDING_QR_DELETES, updated).apply();
        }
        return pendingItems;
    }

    boolean delete(SavedQr pending) {
        try {
            QrVerification verification = verifyOwnedQr(pending);
            if (verification == null) {
                throw new SecurityException("二维码所有权校验失败");
            }
            if (!verification.gone) {
                if (!verification.owned) {
                    throw new SecurityException("二维码不属于本应用");
                }
                String id = pending.uri.getLastPathSegment();
                if (id == null || !id.matches("\\d+")) {
                    throw new SecurityException("无效的 MediaStore 记录 ID");
                }
                String deleteSelection = MediaStore.MediaColumns._ID + "=?";
                String[] deleteArgs = new String[]{id};
                int deleted = context.getContentResolver().delete(
                        MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                        deleteSelection,
                        deleteArgs
                );
                DiagnosticLog.event(
                        "BikeMapQrDelete",
                        "delete uri=" + pending.uri + " rows=" + deleted
                );
                if (deleted <= 0) {
                    throw new IOException("MediaStore 未完成永久删除");
                }
            } else {
                DiagnosticLog.event("BikeMapQrDelete", "row already absent uri=" + pending.uri);
            }

            String dataPath = verification.dataPath != null
                    ? verification.dataPath
                    : pending.dataPath;
            if (dataPath != null && !deleteVerifiedFile(dataPath, pending.fileName)) {
                throw new IOException("无法删除已校验的二维码文件");
            }
            removePendingQr(pending.uri);
            return true;
        } catch (Exception exception) {
            DiagnosticLog.warn("BikeMapQrDelete", "refused or failed to delete QR image", exception);
            return false;
        }
    }

    private SharedPreferences preferences() {
        return context.getSharedPreferences(preferencesName, Context.MODE_PRIVATE);
    }

    private SavedQr parsePendingQr(String entry) {
        String[] parts = entry.split("\\|", -1);
        if (parts.length == 2) {
            return new SavedQr(
                    Long.parseLong(parts[0]),
                    Uri.parse(parts[1]),
                    null,
                    -1L,
                    null,
                    null
            );
        }
        if (parts.length == 7 && "2".equals(parts[0])) {
            return new SavedQr(
                    Long.parseLong(parts[1]),
                    Uri.parse(parts[2]),
                    emptyToNull(parts[3]),
                    Long.parseLong(parts[4]),
                    emptyToNull(parts[5]),
                    emptyToNull(parts[6])
            );
        }
        return null;
    }

    private QrVerification verifyOwnedQr(SavedQr pending) {
        if (pending.uri == null
                || !"content".equals(pending.uri.getScheme())
                || !"media".equals(pending.uri.getAuthority())) {
            return null;
        }
        String id = pending.uri.getLastPathSegment();
        if (id == null || !id.matches("\\d+")) {
            return null;
        }
        Boolean rowExists = mediaRowExists(id);
        if (Boolean.FALSE.equals(rowExists)) {
            // 用户已从相册删除该记录：只清理本机待删除记录，不再碰任何文件。
            return new QrVerification(true, false, null);
        }
        if (rowExists == null) {
            return null;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && hasConflictingOwner(id)) {
            return new QrVerification(false, false, null);
        }

        if (pending.hasFingerprint()) {
            try {
                String dataPath = null;
                String currentHash;
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    currentHash = sha256(pending.uri, null);
                } else {
                    dataPath = pending.dataPath;
                    if (!isExpectedLegacyPath(dataPath)
                            || !pending.fileName.equals(new File(dataPath).getName())) {
                        return new QrVerification(false, false, null);
                    }
                    currentHash = sha256(null, dataPath);
                }
                if (!pending.sha256.equalsIgnoreCase(currentHash)) {
                    return new QrVerification(false, false, null);
                }
                return new QrVerification(false, true, dataPath);
            } catch (Exception exception) {
                return null;
            }
        }

        return verifyLegacyMetadata(id);
    }

    private QrVerification verifyLegacyMetadata(String id) {
        String[] projection;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            projection = new String[]{
                    MediaStore.MediaColumns.DISPLAY_NAME,
                    MediaStore.MediaColumns.MIME_TYPE,
                    MediaStore.MediaColumns.RELATIVE_PATH
            };
        } else {
            projection = new String[]{
                    MediaStore.MediaColumns.DISPLAY_NAME,
                    MediaStore.MediaColumns.MIME_TYPE,
                    MediaStore.MediaColumns.DATA
            };
        }
        try (Cursor cursor = context.getContentResolver().query(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                projection,
                MediaStore.MediaColumns._ID + "=?",
                new String[]{id},
                null
        )) {
            if (cursor == null || !cursor.moveToFirst()) {
                return null;
            }
            String displayName = cursor.getString(
                    cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
            );
            String mimeType = cursor.getString(
                    cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.MIME_TYPE)
            );
            if (!"image/png".equals(mimeType)
                    || displayName == null
                    || !displayName.startsWith(PENDING_QR_PREFIX)
                    || !displayName.endsWith(".png")) {
                return new QrVerification(false, false, null);
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                String relativePath = cursor.getString(
                        cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.RELATIVE_PATH)
                );
                if (!QR_RELATIVE_PATH.equals(normalizeRelativePath(relativePath))) {
                    return new QrVerification(false, false, null);
                }
                return new QrVerification(false, true, null);
            }
            String dataPath = cursor.getString(
                    cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DATA)
            );
            if (!isExpectedLegacyPath(dataPath)
                    || !new File(dataPath).getName().equals(displayName)) {
                return new QrVerification(false, false, null);
            }
            return new QrVerification(false, true, dataPath);
        } catch (Exception exception) {
            return null;
        }
    }

    /** null 表示查询失败，无法确认；false 表示 MediaStore 记录已经不存在。 */
    private Boolean mediaRowExists(String id) {
        try (Cursor cursor = context.getContentResolver().query(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                new String[]{MediaStore.MediaColumns._ID},
                MediaStore.MediaColumns._ID + "=?",
                new String[]{id},
                null
        )) {
            if (cursor == null) {
                return null;
            }
            return cursor.moveToFirst();
        } catch (Exception exception) {
            return null;
        }
    }

    private boolean hasConflictingOwner(String id) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            return false;
        }
        try (Cursor cursor = context.getContentResolver().query(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                new String[]{MediaStore.MediaColumns.OWNER_PACKAGE_NAME},
                MediaStore.MediaColumns._ID + "=?",
                new String[]{id},
                null
        )) {
            if (cursor == null || !cursor.moveToFirst()) {
                return false;
            }
            String owner = cursor.getString(0);
            return owner != null && !context.getPackageName().equals(owner);
        } catch (Exception exception) {
            return false;
        }
    }

    private boolean deleteVerifiedFile(String dataPath, String expectedName) {
        if (dataPath == null || !isExpectedLegacyPath(dataPath)) {
            return false;
        }
        File file = new File(dataPath);
        if (expectedName != null && !expectedName.equals(file.getName())) {
            return false;
        }
        if (!file.exists()) {
            return true;
        }
        return file.delete();
    }

    private boolean isExpectedLegacyPath(String dataPath) {
        if (dataPath == null || dataPath.isEmpty()) {
            return false;
        }
        try {
            File expectedDirectory = new File(
                    Environment.getExternalStoragePublicDirectory(
                            Environment.DIRECTORY_PICTURES
                    ),
                    "BikeMap"
            ).getCanonicalFile();
            File actual = new File(dataPath).getCanonicalFile();
            return expectedDirectory.equals(actual.getParentFile());
        } catch (Exception exception) {
            return false;
        }
    }

    private static String normalizeRelativePath(String relativePath) {
        if (relativePath == null) {
            return null;
        }
        return relativePath.endsWith("/") ? relativePath : relativePath + "/";
    }

    private String sha256(Uri uri, String dataPath) throws Exception {
        try (InputStream input = uri != null
                ? context.getContentResolver().openInputStream(uri)
                : new java.io.FileInputStream(dataPath)) {
            if (input == null) {
                throw new IOException("无法读取二维码图片");
            }
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[8192];
            int read;
            while ((read = input.read(buffer)) != -1) {
                digest.update(buffer, 0, read);
            }
            return toHex(digest.digest());
        }
    }

    private static String sha256(byte[] data) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        return toHex(digest.digest(data));
    }

    private static String toHex(byte[] data) {
        StringBuilder builder = new StringBuilder(data.length * 2);
        for (byte value : data) {
            builder.append(String.format(Locale.US, "%02x", value & 0xff));
        }
        return builder.toString();
    }

    private void removePendingQr(Uri uri) {
        SharedPreferences preferences = preferences();
        Set<String> stored = new HashSet<>(
                preferences.getStringSet(PREF_PENDING_QR_DELETES, new HashSet<>())
        );
        Set<String> updated = new HashSet<>();
        for (String entry : stored) {
            try {
                SavedQr pending = parsePendingQr(entry);
                if (pending == null || !pending.uri.equals(uri)) {
                    updated.add(entry);
                }
            } catch (Exception exception) {
                updated.add(entry);
            }
        }
        preferences.edit().putStringSet(PREF_PENDING_QR_DELETES, updated).apply();
    }

    private static String emptyToNull(String value) {
        return value == null || value.isEmpty() ? null : value;
    }

    static final class SavedQr {
        final long expiresAt;
        final Uri uri;
        final String fileName;
        final long size;
        final String sha256;
        final String dataPath;

        SavedQr(
                long expiresAt,
                Uri uri,
                String fileName,
                long size,
                String sha256,
                String dataPath
        ) {
            this.expiresAt = expiresAt;
            this.uri = uri;
            this.fileName = fileName;
            this.size = size;
            this.sha256 = sha256;
            this.dataPath = dataPath == null || dataPath.isEmpty() ? null : dataPath;
        }

        boolean hasFingerprint() {
            return fileName != null && size >= 0L && sha256 != null;
        }

        String serialize() {
            return "2|" + expiresAt
                    + "|" + uri
                    + "|" + safe(fileName)
                    + "|" + size
                    + "|" + safe(sha256)
                    + "|" + safe(dataPath);
        }

        private static String safe(String value) {
            return value == null ? "" : value;
        }
    }

    private static final class QrVerification {
        final boolean gone;
        final boolean owned;
        final String dataPath;

        QrVerification(boolean gone, boolean owned, String dataPath) {
            this.gone = gone;
            this.owned = owned;
            this.dataPath = dataPath;
        }
    }
}
