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
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class BikeRepository {
    private static final String API_URL =
            "https://api.kvcoogo.com/ManagerApi/api/v1.0.0/queryNearbyCar";
    private static final DateTimeFormatter LAST_UPLOAD_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final ZoneId SERVICE_ZONE = ZoneId.of("Asia/Shanghai");

    public interface Callback {
        void onSuccess(List<Bike> bikes, long fetchedAt);

        void onError(String message);
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

    private final Context context;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    public BikeRepository(Context context) {
        this.context = context.getApplicationContext();
    }
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    public void fetchNearby(double centerLatitude, double centerLongitude, Callback callback) {
        executor.execute(() -> {
            try {
                List<Bike> bikes = request(centerLatitude, centerLongitude);
                mainHandler.post(() -> callback.onSuccess(bikes, System.currentTimeMillis()));
            } catch (Exception exception) {
                String finalMessage = toUserMessage(exception);
                mainHandler.post(() -> callback.onError(finalMessage));
            }
        });
    }

    public void shutdown() {
        executor.shutdownNow();
    }

    private List<Bike> request(double centerLatitude, double centerLongitude) throws Exception {
        String response = executeRequest(centerLatitude, centerLongitude);
        return parseBikes(response, centerLatitude, centerLongitude);
    }

    private String executeRequest(double centerLatitude, double centerLongitude) throws Exception {
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL(API_URL).openConnection();
            connection.setRequestMethod("POST");
            connection.setConnectTimeout(10_000);
            connection.setReadTimeout(15_000);
            connection.setDoOutput(true);
            connection.setRequestProperty(
                    "Content-Type",
                    "application/x-www-form-urlencoded; charset=UTF-8"
            );
            connection.setRequestProperty("User-Agent", "BikeMap/" + BuildConfig.VERSION_NAME);

            String body = "lat=" + centerLatitude
                    + "&lng=" + centerLongitude
                    + "&deviceType=1";
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

    private List<Bike> parseBikes(
            String response,
            double centerLatitude,
            double centerLongitude
    ) throws Exception {
        JSONObject root = new JSONObject(response);
        if (root.optInt("errorCode", -1) != 0) {
            throw new ServiceException(safeServiceMessage(
                    root.optString("resultMsg", "")
            ));
        }

        JSONObject result = root.optJSONObject("result");
        JSONArray carList = result == null ? null : result.optJSONArray("carList");
        if (carList == null) {
            return Collections.emptyList();
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

    private Bike parseBike(
            JSONObject car,
            double centerLatitude,
            double centerLongitude
    ) {
        String number = VehicleNumberCatalog.normalize(car.optString("carNum", ""));
        double latitude = car.optDouble("lat", Double.NaN);
        double longitude = car.optDouble("lng", Double.NaN);
        if (!number.matches("\\d{9}")
                || "000000000".equals(number)
                || !isValidCoordinate(latitude, longitude)) {
            return null;
        }

        double battery = car.optDouble("currentPercent", -1);
        long lastUploadAtMs = parseLastUploadAt(
                car.optString("lastUploadTime", "")
        );
        double lowBattery = car.optDouble("lowBattery", Double.NaN);
        boolean online = car.optInt("onlineStatus", 0) == 1;
        boolean enabled = car.optInt("status", 0) == 1;
        boolean enoughBattery = Double.isNaN(lowBattery) || battery > lowBattery;
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
        return new Bike(
                number,
                latitude,
                longitude,
                battery,
                statusRes,
                car.optString("carTypeName", ""),
                car.optString("servicesiteName", "").trim(),
                correctSiteName(car.optString("givecarName", "").trim()),
                distance,
                available,
                true,
                lastUploadAtMs
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
        if (exception instanceof SocketTimeoutException) {
            return context.getString(Error.TIMEOUT.messageRes);
        }
        if (exception instanceof UnknownHostException) {
            return context.getString(Error.NETWORK.messageRes);
        }
        if (exception instanceof IOException) {
            return context.getString(Error.NETWORK.messageRes);
        }
        if (exception instanceof org.json.JSONException) {
            return context.getString(Error.PARSE.messageRes);
        }
        return context.getString(Error.UNKNOWN.messageRes);
    }

    private static final class ServiceException extends Exception {
        ServiceException(String message) {
            super(message);
        }
    }

    private String serviceMessageFromResponse(String response) {
        try {
            JSONObject root = new JSONObject(response);
            return safeServiceMessage(root.optString("resultMsg", ""));
        } catch (org.json.JSONException ignored) {
            return context.getString(Error.SERVICE.messageRes);
        }
    }

    private String safeServiceMessage(String message) {
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

    private static boolean isValidCoordinate(double latitude, double longitude) {
        return !Double.isNaN(latitude)
                && !Double.isNaN(longitude)
                && latitude >= -90
                && latitude <= 90
                && longitude >= -180
                && longitude <= 180
                && (latitude != 0 || longitude != 0);
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
    private static String correctSiteName(String value) {
        if (value == null || value.isEmpty()) {
            return "";
        }
        String corrected = value;
        corrected = corrected.replace("挤民楼", "济民楼");
        corrected = corrected.replace("先啸楼", "先骕楼");
        return corrected;
    }
}
