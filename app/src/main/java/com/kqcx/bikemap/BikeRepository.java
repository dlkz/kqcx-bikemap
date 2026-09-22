package com.kqcx.bikemap;

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
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class BikeRepository {
    private static final String API_URL =
            "https://api.kvcoogo.com/ManagerApi/api/v1.0.0/queryNearbyCar";

    public interface Callback {
        void onSuccess(List<Bike> bikes, long fetchedAt);

        void onError(String message);
    }

    public enum Error {
        NETWORK("网络连接失败，请检查网络后重试"),
        TIMEOUT("网络请求超时，请稍后重试"),
        PARSE("车辆数据解析失败，请稍后重试"),
        SERVICE("车辆服务暂时不可用，请稍后重试"),
        UNKNOWN("车辆数据获取失败，请稍后重试");

        public final String message;

        Error(String message) {
            this.message = message;
        }
    }

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
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
            connection.setRequestProperty("User-Agent", "BikeMap/1.0");

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
                throw new ServiceException(Error.SERVICE.message);
            }

            JSONObject root = new JSONObject(response);
            if (root.optInt("errorCode", -1) != 0) {
                throw new ServiceException(Error.SERVICE.message);
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

                long number = parseNumber(car.optString("carNum", ""));
                double latitude = car.optDouble("lat", Double.NaN);
                double longitude = car.optDouble("lng", Double.NaN);
                if (number <= 0 || !isValidCoordinate(latitude, longitude)) {
                    continue;
                }

                double battery = car.optDouble("currentPercent", -1);
                double lowBattery = car.optDouble("lowBattery", Double.NaN);
                boolean online = car.optInt("onlineStatus", 0) == 1;
                boolean enabled = car.optInt("status", 0) == 1;
                boolean enoughBattery = Double.isNaN(lowBattery) || battery > lowBattery;
                boolean available = online && enabled && enoughBattery;

                String status;
                if (!online) {
                    status = "离线";
                } else if (!enabled) {
                    status = "不可用";
                } else if (!enoughBattery) {
                    status = "电量低";
                } else {
                    status = "可用";
                }

                float distance = distanceMeters(
                        centerLatitude,
                        centerLongitude,
                        latitude,
                        longitude
                );
                Bike bike = new Bike(
                        number,
                        latitude,
                        longitude,
                        battery,
                        status,
                        car.optString("carTypeName", ""),
                        car.optString("servicesiteName", "").trim(),
                        correctSiteName(car.optString("givecarName", "").trim()),
                        distance,
                        available,
                        true
                );
                bikes.add(bike);
            }

            bikes.sort(Comparator.comparingDouble(bike -> bike.distanceMeters));
            return bikes;
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
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

    private static String toUserMessage(Exception exception) {
        if (exception instanceof ServiceException) {
            return exception.getMessage();
        }
        if (exception instanceof SocketTimeoutException) {
            return Error.TIMEOUT.message;
        }
        if (exception instanceof UnknownHostException) {
            return Error.NETWORK.message;
        }
        if (exception instanceof IOException) {
            return Error.NETWORK.message;
        }
        if (exception instanceof org.json.JSONException) {
            return Error.PARSE.message;
        }
        return Error.UNKNOWN.message;
    }

    private static final class ServiceException extends Exception {
        ServiceException(String message) {
            super(message);
        }
    }

    private static long parseNumber(String value) {
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException exception) {
            return -1;
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
