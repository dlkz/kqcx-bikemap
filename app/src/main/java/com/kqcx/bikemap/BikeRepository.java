package com.kqcx.bikemap;

import android.content.Context;
import android.location.Location;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class BikeRepository {
    private static final String API_URL =
            "https://api.kvcoogo.com/ManagerApi/api/v1.0.0/queryNearbyCar";
    /**
     * 按完整车号点名查一辆车。附近列表里没有目标车时的兜底，与官方扫码后
     * loadOneCar 同款；坐标只是查询上下文，接口不需要凭证，与附近查询同口径。
     */
    private static final String ONE_CAR_URL =
            "https://api.kvcoogo.com/ManagerApi/api/v2.0.0/queryOneCar";
    private static final int ERROR_VEHICLE_UNAVAILABLE = 50006;
    private static final DateTimeFormatter LAST_UPLOAD_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final ZoneId SERVICE_ZONE = ZoneId.of("Asia/Shanghai");

    public interface Callback {
        void onSuccess(List<Bike> bikes, long fetchedAt);

        void onError(RepositoryError error);
    }

    /** 按编号点名查单辆车的回调；查不到这辆车时 bike 为 null。 */
    public interface BikeCallback {
        void onSuccess(Bike bike);

        void onError(String message);

        default void onUnavailable(int errorCode, String message) {
            onError(message);
        }
    }

    /** 兜底提示的字符串资源 id，实际文案在 strings.xml 中维护。 */
    public enum Error {
        NETWORK(R.string.repository_error_network),
        TIMEOUT(R.string.repository_error_timeout),
        PARSE(R.string.repository_error_parse),
        SERVICE(R.string.repository_error_service),
        UNKNOWN(R.string.repository_error_unknown);

        final int messageRes;

        Error(int messageRes) {
            this.messageRes = messageRes;
        }
    }

    public static final class RepositoryError {
        private final Error code;
        private final String userMessage;

        RepositoryError(Error code, String userMessage) {
            this.code = code;
            this.userMessage = userMessage;
        }

        public Error code() {
            return code;
        }

        /** UI-only text. It may come from the upstream service and must not be logged. */
        public String userMessage() {
            return userMessage;
        }
    }

    private final Context context;
    private final Object executorLock = new Object();
    private ExecutorService executor = Executors.newSingleThreadExecutor();

    public BikeRepository(Context context) {
        this.context = context.getApplicationContext();
    }
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    public void fetchNearby(double centerLatitude, double centerLongitude, Callback callback) {
        execute(() -> {
            try {
                String response = executePost(
                        API_URL,
                        "lat=" + centerLatitude
                                + "&lng=" + centerLongitude
                                + "&deviceType=1"
                );
                List<Bike> bikes = parseBikes(
                        response,
                        centerLatitude,
                        centerLongitude
                );
                mainHandler.post(() -> callback.onSuccess(bikes, System.currentTimeMillis()));
            } catch (Exception exception) {
                RepositoryError error = repositoryError(exception);
                mainHandler.post(() -> callback.onError(error));
            }
        });
    }

    /**
     * 按完整车号点名查询一辆车。与附近查询共用同一个单线程 executor，避免两条
     * 请求链路上的连接互相干扰；查不到（车号不存在 / 坐标无效）按正常结果回调
     * null；租用中等业务状态走 onUnavailable，其他网络或服务故障才走 onError。
     */
    public void queryOneCar(
            String carNum,
            double centerLatitude,
            double centerLongitude,
            BikeCallback callback
    ) {
        execute(() -> {
            try {
                String response = executePost(
                        ONE_CAR_URL,
                        "carNum=" + VehicleNumberCatalog.normalize(carNum)
                                + "&lat=" + centerLatitude
                                + "&lng=" + centerLongitude
                );
                Bike bike = parseOneCar(
                        response,
                        carNum,
                        centerLatitude,
                        centerLongitude
                );
                mainHandler.post(() -> callback.onSuccess(bike));
            } catch (ServiceException exception) {
                String finalMessage = toUserMessage(exception);
                mainHandler.post(() -> dispatchBikeCallbackError(
                        exception.errorCode,
                        finalMessage,
                        callback
                ));
            } catch (Exception exception) {
                String finalMessage = toUserMessage(exception);
                mainHandler.post(() -> callback.onError(finalMessage));
            }
        });
    }

    /**
     * 结束当前链路并换用新的单线程 executor，避免旧的慢请求阻塞后续检查。
     * 已经投递到主线程队列的旧回调由调用方的 generation 负责忽略。
     */
    public void cancelPending() {
        ExecutorService previous;
        synchronized (executorLock) {
            previous = executor;
            executor = Executors.newSingleThreadExecutor();
        }
        if (previous != null) {
            previous.shutdownNow();
        }
    }

    public void shutdown() {
        ExecutorService previous;
        synchronized (executorLock) {
            previous = executor;
            executor = null;
        }
        if (previous != null) {
            previous.shutdownNow();
        }
    }

    private void execute(Runnable task) {
        synchronized (executorLock) {
            if (executor == null || executor.isShutdown()) {
                return;
            }
            executor.execute(task);
        }
    }

    private String executePost(String url, String body) throws Exception {
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL(url).openConnection();
            connection.setRequestMethod("POST");
            connection.setConnectTimeout(10_000);
            connection.setReadTimeout(15_000);
            connection.setDoOutput(true);
            connection.setRequestProperty(
                    "Content-Type",
                    "application/x-www-form-urlencoded; charset=UTF-8"
            );
            connection.setRequestProperty("User-Agent", "BikeMap/" + BuildConfig.VERSION_NAME);

            try (OutputStream output = connection.getOutputStream()) {
                output.write(body.getBytes(StandardCharsets.UTF_8));
            }

            int statusCode = connection.getResponseCode();
            InputStream stream = statusCode >= 200 && statusCode < 300
                    ? connection.getInputStream()
                    : connection.getErrorStream();
            String response = readAll(stream);
            if (statusCode < 200 || statusCode >= 300) {
                throw new ServiceException(serviceMessageFromResponse(response));
            }
            return response;
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    /**
     * 解析「按车号查单车」的响应。信封返回业务错误（errorCode 非 0）时抛出带
     * 服务端 resultMsg 的 ServiceException，让上层给出更具体的提示；信封成功但
     * 解析不出车返回 null，调用方把“查不到”当正常结果提示。成品对象的字段口径与
     * 附近列表同源，但缺字段的假设更宽松（见
     * {@link #parseBike(JSONObject, double, double, boolean)}）。
     */
    private Bike parseOneCar(
            String response,
            String requestedNumber,
            double centerLatitude,
            double centerLongitude
    ) throws Exception {
        JSONObject root = new JSONObject(response);
        throwIfEnvelopeError(root);
        JSONObject car = root.optJSONObject("result");
        if (car != null) {
            Bike parsed = parseBike(car, centerLatitude, centerLongitude, true);
            return matchesRequestedNumber(requestedNumber, parsed) ? parsed : null;
        }
        JSONArray list = root.optJSONArray("result");
        if (list != null) {
            for (int index = 0; index < list.length(); index++) {
                JSONObject item = list.optJSONObject(index);
                if (item == null) {
                    continue;
                }
                Bike parsed = parseBike(item, centerLatitude, centerLongitude, true);
                if (matchesRequestedNumber(requestedNumber, parsed)) {
                    return parsed;
                }
            }
        }
        return null;
    }

    static boolean matchesRequestedNumber(String requestedNumber, Bike bike) {
        return bike != null
                && VehicleNumberCatalog.normalize(requestedNumber).equals(bike.number);
    }

    private List<Bike> parseBikes(
            String response,
            double centerLatitude,
            double centerLongitude
    ) throws Exception {
        JSONObject root = new JSONObject(response);
        throwIfEnvelopeError(root);

        JSONObject result = root.optJSONObject("result");
        if (result == null) {
            throw new org.json.JSONException("missing result");
        }
        JSONArray carList = result.optJSONArray("carList");
        if (carList == null) {
            throw new org.json.JSONException("missing carList");
        }

        List<Bike> bikes = new ArrayList<>();
        for (int index = 0; index < carList.length(); index++) {
            JSONObject car = carList.optJSONObject(index);
            if (car == null) {
                continue;
            }
            Bike bike = parseBike(car, centerLatitude, centerLongitude);
            if (bike != null) {
                bikes.add(bike);
            }
        }
        bikes.sort(Comparator.comparingDouble(bike -> bike.distanceMeters));
        return bikes;
    }

    /**
     * 统一校验接口信封。errorCode 非 0 表示服务端业务错误，抛出带 resultMsg 的
     * ServiceException；resultMsg 缺失或不安全时由 {@link #safeDisplayMessage}
     * 回落为通用服务提示。
     */
    private void throwIfEnvelopeError(JSONObject root) throws ServiceException {
        int errorCode = errorCodeFromEnvelope(root);
        if (errorCode == 0) {
            return;
        }
        throw new ServiceException(
                errorCode,
                safeDisplayMessage(root.optString("resultMsg", ""))
        );
    }

    static int errorCodeFromEnvelope(JSONObject root) {
        return root.optInt("errorCode", -1);
    }

    private Bike parseBike(
            JSONObject car,
            double centerLatitude,
            double centerLongitude
    ) {
        return parseBike(car, centerLatitude, centerLongitude, false);
    }

    /**
     * @param lenientStatus 附近列表一次拿一批、坏了丢一条，在线/启用字段缺失按坏数
     *                      据处理；按编号点名查的详情接口只确认过 carNum/lat/lng/
     *                      currentPercent，缺字段时不该把一辆真车标成失联。
     */
    private Bike parseBike(
            JSONObject car,
            double centerLatitude,
            double centerLongitude,
            boolean lenientStatus
    ) {
        String number = VehicleNumberCatalog.normalize(car.optString("carNum", ""));
        double latitude = car.optDouble("lat", Double.NaN);
        double longitude = car.optDouble("lng", Double.NaN);
        if (!number.matches("\\d{9}")
                || "000000000".equals(number)
                || !CoordinateUtils.isValidLatLng(latitude, longitude)) {
            return null;
        }

        double battery = car.optDouble("currentPercent", -1);
        long lastUploadAtMs = parseLastUploadAt(
                car.optString("lastUploadTime", "")
        );
        double lowBattery = car.optDouble("lowBattery", Double.NaN);
        boolean online = car.has("onlineStatus")
                ? car.optInt("onlineStatus", 0) == 1
                : lenientStatus;
        boolean enabled = car.has("status")
                ? car.optInt("status", 0) == 1
                : lenientStatus;
        boolean enoughBattery = Double.isNaN(lowBattery)
                || (lenientStatus && battery <= 0)
                || battery > lowBattery;
        boolean available = online && enabled && enoughBattery;

        int statusRes;
        if (!online) {
            statusRes = R.string.bike_status_offline;
        } else if (!enabled) {
            statusRes = R.string.bike_status_unavailable;
        } else if (!enoughBattery) {
            statusRes = R.string.bike_status_low_battery;
        } else {
            statusRes = R.string.bike_status_available;
        }

        float distance = distanceMeters(
                centerLatitude,
                centerLongitude,
                latitude,
                longitude
        );
        String bluetoothName = car.isNull("bluetoothName")
                ? ""
                : car.optString("bluetoothName", "").trim();
        return new Bike(
                number,
                latitude,
                longitude,
                battery,
                statusRes,
                car.optString("carTypeName", ""),
                correctSiteName(car.optString("servicesiteName", "").trim()),
                correctSiteName(car.optString("givecarName", "").trim()),
                distance,
                available,
                true,
                lastUploadAtMs,
                bluetoothName
        );
    }
    private static String readAll(InputStream stream) throws Exception {
        if (stream == null) {
            return "";
        }

        StringBuilder builder = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(stream, StandardCharsets.UTF_8)
        )) {
            String line;
            while ((line = reader.readLine()) != null) {
                builder.append(line);
            }
        }
        return builder.toString();
    }

    private String toUserMessage(Exception exception) {
        if (exception instanceof ServiceException) {
            return exception.getMessage();
        }
        return context.getString(errorCode(exception).messageRes);
    }

    private RepositoryError repositoryError(Exception exception) {
        return new RepositoryError(errorCode(exception), toUserMessage(exception));
    }

    private Error errorCode(Exception exception) {
        if (exception instanceof ServiceException) {
            return Error.SERVICE;
        }
        if (exception instanceof SocketTimeoutException) {
            return Error.TIMEOUT;
        }
        if (exception instanceof UnknownHostException) {
            return Error.NETWORK;
        }
        if (exception instanceof IOException) {
            return Error.NETWORK;
        }
        if (exception instanceof org.json.JSONException) {
            return Error.PARSE;
        }
        return Error.UNKNOWN;
    }

    private static final class ServiceException extends Exception {
        final int errorCode;

        ServiceException(String message) {
            this(Integer.MIN_VALUE, message);
        }

        ServiceException(int errorCode, String message) {
            super(message);
            this.errorCode = errorCode;
        }
    }

    static boolean isVehicleUnavailableErrorCode(int errorCode) {
        return errorCode == ERROR_VEHICLE_UNAVAILABLE;
    }

    static void dispatchBikeCallbackError(
            int errorCode,
            String message,
            BikeCallback callback
    ) {
        if (isVehicleUnavailableErrorCode(errorCode)) {
            callback.onUnavailable(errorCode, message);
        } else {
            callback.onError(message);
        }
    }

    private String serviceMessageFromResponse(String response) {
        try {
            JSONObject root = new JSONObject(response);
            return safeDisplayMessage(root.optString("resultMsg", ""));
        } catch (org.json.JSONException ignored) {
            return context.getString(Error.SERVICE.messageRes);
        }
    }

    /**
     * Filters untrusted upstream text for UI display only.
     *
     * <p>This is not a diagnostic-log sanitizer. Callers must never pass the
     * returned or original upstream message to {@link DiagnosticLog}.</p>
     */
    private String safeDisplayMessage(String message) {
        if (message == null) {
            return context.getString(Error.SERVICE.messageRes);
        }
        String normalized = message.trim().replaceAll("\\s+", " ");
        if (normalized.isEmpty()
                || normalized.length() > 80
                || normalized.contains("://")
                || normalized.contains("/")
                || normalized.contains("\\")
                || normalized.contains(" at ")
                || normalized.startsWith("<")) {
            return context.getString(Error.SERVICE.messageRes);
        }
        for (int index = 0; index < normalized.length(); index++) {
            if (Character.isISOControl(normalized.charAt(index))) {
                return context.getString(Error.SERVICE.messageRes);
            }
        }
        return normalized;
    }

    private static long parseLastUploadAt(String value) {
        if (value == null || value.trim().isEmpty()) {
            return 0L;
        }
        try {
            return LocalDateTime.parse(value.trim(), LAST_UPLOAD_FORMAT)
                    .atZone(SERVICE_ZONE)
                    .toInstant()
                    .toEpochMilli();
        } catch (DateTimeParseException ignored) {
            return 0L;
        }
    }

    private static float distanceMeters(
            double startLatitude,
            double startLongitude,
            double endLatitude,
            double endLongitude
    ) {
        float[] result = new float[1];
        Location.distanceBetween(startLatitude, startLongitude, endLatitude, endLongitude, result);
        return result[0];
    }

    /**
     * The vehicle service stores its own point names. A few of them contain
     * typos that make a building impossible to recognise, so the known ones are
     * normalised here instead of showing the raw vendor value.
     */
    static String correctSiteName(String value) {
        if (value == null || value.isEmpty()) {
            return "";
        }
        String corrected = value;
        corrected = corrected.replace("南昌工程学院", "江西水利电力大学");
        // 服务对站点 67 用简称“江西师大”，与内置目录的“江西师范大学”对齐；
        // 必须整串匹配，否则会误伤已经带“大学”的全称。
        if (corrected.equals("江西师大")) {
            corrected = "江西师范大学";
        }
        corrected = corrected.replace("挤名楼", "济民楼");
        corrected = corrected.replace("挤民楼", "济民楼");
        corrected = corrected.replace("挤明楼", "济民楼");
        corrected = corrected.replace("先啸楼", "先骕楼");
        return corrected;
    }
}
