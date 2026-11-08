package com.hp.smsforward;

import android.os.Build;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Generic HTTPS JSON webhook. Payload:
 * {event, from, text, received_at, device, android, message_id}.
 */
final class WebhookForwarder {
    private static final int CONNECT_TIMEOUT_MS = 15_000;
    private static final int READ_TIMEOUT_MS = 25_000;

    private WebhookForwarder() {
    }

    static void send(Config config, SmsRecord record) throws Exception {
        String error = config.validateForForwarding();
        if (error != null) throw new IllegalArgumentException(error);

        JSONObject payload = new JSONObject();
        payload.put("event", "sms.received");
        payload.put("from", record.sender);
        payload.put("text", record.body);
        payload.put("received_at", isoTime(record.receivedAt));
        payload.put("device", deviceName());
        payload.put("android", Build.VERSION.RELEASE);
        payload.put("message_id", record.id);
        sendJson(config.webhookUrl, payload.toString());
    }

    static void sendTest(Config config, String text) throws Exception {
        String error = config.validateForForwarding();
        if (error != null) throw new IllegalArgumentException(error);

        JSONObject payload = new JSONObject();
        payload.put("event", "sms.test");
        payload.put("from", "短信转发");
        payload.put("text", text);
        payload.put("received_at", isoTime(System.currentTimeMillis()));
        payload.put("device", deviceName());
        payload.put("android", Build.VERSION.RELEASE);
        payload.put("message_id", 0);
        sendJson(config.webhookUrl, payload.toString());
    }

    private static void sendJson(String urlValue, String json) throws Exception {
        URL url = new URL(urlValue);
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        try {
            connection.setRequestMethod("POST");
            connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
            connection.setReadTimeout(READ_TIMEOUT_MS);
            connection.setDoOutput(true);
            connection.setInstanceFollowRedirects(false);
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            connection.setRequestProperty("Accept", "application/json");
            connection.setRequestProperty("User-Agent", "SmsForwarder/1.0");

            byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
            connection.setFixedLengthStreamingMode(bytes.length);
            try (OutputStream out = connection.getOutputStream()) {
                out.write(bytes);
            }

            int code = connection.getResponseCode();
            String response = readLimited(connection, code);
            if (code < 200 || code >= 300) {
                String detail = response.isEmpty() ? "" : "：" + response;
                throw new IllegalStateException("Webhook 返回 HTTP " + code + detail);
            }
        } finally {
            connection.disconnect();
        }
    }

    private static String readLimited(HttpURLConnection connection, int code) {
        InputStream stream = null;
        try {
            stream = code >= 400 ? connection.getErrorStream() : connection.getInputStream();
            if (stream == null) return "";
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            byte[] chunk = new byte[1024];
            int total = 0;
            int count;
            while ((count = stream.read(chunk)) != -1 && total < 4096) {
                int keep = Math.min(count, 4096 - total);
                buffer.write(chunk, 0, keep);
                total += keep;
            }
            return new String(buffer.toByteArray(), StandardCharsets.UTF_8)
                    .replace('\r', ' ').replace('\n', ' ').trim();
        } catch (Exception ignored) {
            return "";
        } finally {
            if (stream != null) {
                try {
                    stream.close();
                } catch (Exception ignored) {
                    // Best effort.
                }
            }
        }
    }

    private static String isoTime(long millis) {
        return new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.US)
                .format(new Date(millis));
    }

    private static String deviceName() {
        String manufacturer = Build.MANUFACTURER == null ? "" : Build.MANUFACTURER.trim();
        String model = Build.MODEL == null ? "" : Build.MODEL.trim();
        if (model.toLowerCase(Locale.US).startsWith(manufacturer.toLowerCase(Locale.US))) {
            return model;
        }
        return (manufacturer + " " + model).trim();
    }
}
