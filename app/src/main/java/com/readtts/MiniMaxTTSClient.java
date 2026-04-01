package com.readtts;

import java.io.OutputStream;
import java.io.InputStream;
import java.io.ByteArrayOutputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * MiniMax TTS API Client
 */
public class MiniMaxTTSClient {

    private static final String API_URL = "https://api.minimax.chat/v1/t2a_v2";
    private static final int TIMEOUT_CONNECT = 15000;
    private static final int TIMEOUT_READ = 30000;

    public static byte[] synthesize(String apiKey, String text, String voiceId, float speed) throws Exception {
        if (text == null || text.isEmpty()) {
            throw new IllegalArgumentException("Text cannot be empty");
        }

        // Clean text
        text = text.replaceAll("[\\x00-\\x1F\\x7F-\\x9F]", "").trim();
        if (text.length() > 1000) {
            text = text.substring(0, 1000);
        }

        String jsonBody = buildRequest(text, voiceId, speed);

        URL url = new URL(API_URL);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();

        try {
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Authorization", "Bearer " + apiKey);
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setDoOutput(true);
            conn.setConnectTimeout(TIMEOUT_CONNECT);
            conn.setReadTimeout(TIMEOUT_READ);

            // Write body
            OutputStream os = conn.getOutputStream();
            os.write(jsonBody.getBytes("UTF-8"));
            os.close();

            int code = conn.getResponseCode();
            if (code != 200) {
                String error = readError(conn);
                throw new RuntimeException("MiniMax API error: " + code + " - " + error);
            }

            // Read audio data
            InputStream is = conn.getInputStream();
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int len;
            while ((len = is.read(buf)) != -1) {
                bos.write(buf, 0, len);
            }
            is.close();
            return bos.toByteArray();

        } finally {
            conn.disconnect();
        }
    }

    private static String buildRequest(String text, String voiceId, float speed) {
        StringBuilder sb = new StringBuilder();
        sb.append("{");
        sb.append("\"model\":\"speech-02-hd\",");
        sb.append("\"text\":\"").append(escapeJson(text)).append("\",");
        sb.append("\"stream\":false,");
        sb.append("\"voice_setting\":{");
        sb.append("\"voice_id\":\"").append(voiceId).append("\",");
        sb.append("\"speed\":").append(speed).append(",");
        sb.append("\"pitch\":0,");
        sb.append("\"volume\":1.0},");
        sb.append("\"audio_setting\":{");
        sb.append("\"audio_format\":\"mp3\",");
        sb.append("\"sample_rate\":32000");
        sb.append("}}");
        return sb.toString();
    }

    private static String escapeJson(String s) {
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }

    private static String readError(HttpURLConnection conn) {
        try {
            InputStream is = conn.getErrorStream();
            if (is != null) {
                ByteArrayOutputStream bos = new ByteArrayOutputStream();
                byte[] buf = new byte[1024];
                int len;
                while ((len = is.read(buf)) != -1) {
                    bos.write(buf, 0, len);
                }
                return new String(bos.toByteArray(), "UTF-8");
            }
        } catch (Exception ignored) {}
        return "unknown";
    }
}
