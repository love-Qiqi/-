package com.readtts;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XSharedPreferences;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * ReadTTS - MiniMax TTS for Reading App
 * LSPosed Hook Entry Point
 */
public class XposedInit implements IXposedHookLoadPackage {

    private static final String TAG = "ReadTTS";
    private static final String TARGET_PKG = "com.chaoxning.reading";
    private static final String API_URL = "https://api.minimax.chat/v1/t2a_v2";

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) throws Throwable {
        if (!lpparam.packageName.equals(TARGET_PKG)) return;

        Log.d(TAG, "ReadTTS: Hooking " + TARGET_PKG);

        // Load settings from module's shared prefs
        XSharedPreferences prefs = new XSharedPreferences("com.readtts", "readtts_config");
        prefs.makeWorldReadable();
        final String apiKey = prefs.getString("api_key", "");
        final String voiceId = prefs.getString("voice_id", "female-tianmei");
        final float speed = prefs.getFloat("speed", 1.0f);
        final boolean enabled = prefs.getBoolean("enabled", true);

        if (!enabled || apiKey == null || apiKey.isEmpty()) {
            Log.d(TAG, "ReadTTS disabled or no API key, skipping");
            return;
        }

        Log.d(TAG, "ReadTTS enabled, API key present, hooking TTS...");

        // Hook TTS methods - try multiple class names
        hookTTSClass(lpparam.classLoader, apiKey, voiceId, speed);

        Log.d(TAG, "ReadTTS: Hook complete");
    }

    private void hookTTSClass(ClassLoader classLoader, String apiKey, String voiceId, float speed) {
        String[] classNames = {
            "com.chaoxning.tts.TtsManager",
            "com.chaoxning.tts.TtsHelper",
            "com.chaoxning.tts.TtsPlayer",
            "com.chaoxning.tts.TtsImpl",
            "com.chaoxning.tts.SpeechSynthesizer"
        };

        for (String className : classNames) {
            try {
                Class<?> cls = classLoader.loadClass(className);
                Log.d(TAG, "Found TTS class: " + className);
                hookAllSpeakMethods(cls, apiKey, voiceId, speed);
                return;
            } catch (ClassNotFoundException ignored) {
            } catch (Throwable t) {
                Log.e(TAG, "Error loading " + className + ": " + t.getMessage());
            }
        }

        // If no specific TTS class found, try to hook at the activity level
        hookActivityLevel(classLoader, apiKey, voiceId, speed);
    }

    private void hookAllSpeakMethods(Class<?> cls, String apiKey, String voiceId, float speed) {
        java.lang.reflect.Method[] methods = cls.getDeclaredMethods();
        for (java.lang.reflect.Method method : methods) {
            Class<?>[] params = method.getParameterTypes();
            String name = method.getName().toLowerCase();

            // Only hook TTS-related methods
            if (name.contains("speak") || name.contains("play") || name.contains("tts")) {
                try {
                    if (params.length == 1 && params[0] == String.class) {
                        de.robv.android.xposed.XposedHelpers.findAndHookMethod(cls, method.getName(),
                            String.class,
                            new XC_MethodHook() {
                                @Override
                                protected void beforeHookedMethod(XC_MethodHook.MethodHookParam param) throws Throwable {
                                    String text = (String) param.args[0];
                                    if (text != null && !text.isEmpty()) {
                                        Log.d(TAG, "TTS intercept: " + text.substring(0, Math.min(30, text.length())));
                                        param.setResult(null);
                                        callMiniMaxTTS(apiKey, voiceId, speed, text);
                                    }
                                }
                            });
                        Log.d(TAG, "Hooked: " + method.getName() + "(String)");
                    } else if (params.length >= 2 && params[0] == String.class && params[1] == int.class) {
                        de.robv.android.xposed.XposedHelpers.findAndHookMethod(cls, method.getName(),
                            params,
                            new XC_MethodHook() {
                                @Override
                                protected void beforeHookedMethod(XC_MethodHook.MethodHookParam param) throws Throwable {
                                    String text = (String) param.args[0];
                                    if (text != null && !text.isEmpty()) {
                                        Log.d(TAG, "TTS intercept: " + text.substring(0, Math.min(30, text.length())));
                                        param.setResult(null);
                                        int queue = params.length > 1 && param.args.length > 1 ? (Integer) param.args[1] : 0;
                                        callMiniMaxTTS(apiKey, voiceId, speed, text);
                                    }
                                }
                            });
                        Log.d(TAG, "Hooked: " + method.getName() + "(String, int...)");
                    }
                } catch (Throwable t) {
                    Log.d(TAG, "Could not hook " + method.getName() + ": " + t.getMessage());
                }
            }
        }
    }

    private void hookActivityLevel(ClassLoader classLoader, String apiKey, String voiceId, float speed) {
        // Try hooking any class that has TTS-related speak methods
        try {
            Class<?> activityClass = classLoader.loadClass("android.app.Activity");
            de.robv.android.xposed.XposedHelpers.findAndHookMethod(activityClass, "onCreate",
                android.os.Bundle.class,
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(XC_MethodHook.MethodHookParam param) throws Throwable {
                        Log.d(TAG, "Activity created, scanning for TTS...");
                    }
                });
        } catch (Throwable t) {
            Log.d(TAG, "Activity hook failed: " + t.getMessage());
        }
    }

    private void callMiniMaxTTS(String apiKey, String voiceId, float speed, String text) {
        new Thread(() -> {
            try {
                String json = buildRequest(text, voiceId, speed);

                java.net.URL url = new java.net.URL(API_URL);
                java.net.HttpURLConnection conn = (java.net.HttpURLConnection) url.openConnection();
                conn.setRequestMethod("POST");
                conn.setRequestProperty("Authorization", "Bearer " + apiKey);
                conn.setRequestProperty("Content-Type", "application/json");
                conn.setDoOutput(true);
                conn.setConnectTimeout(10000);
                conn.setReadTimeout(30000);

                conn.getOutputStream().write(json.getBytes("UTF-8"));

                int code = conn.getResponseCode();
                if (code == 200) {
                    byte[] audio = readAll(conn.getInputStream());
                    conn.disconnect();
                    playAudio(audio);
                    Log.d(TAG, "MiniMax TTS played: " + audio.length + " bytes");
                } else {
                    String err = readAll(conn.getErrorStream());
                    Log.e(TAG, "MiniMax TTS error: " + code + " " + err);
                }
            } catch (Throwable t) {
                Log.e(TAG, "TTS error: " + t.getMessage());
            }
        }).start();
    }

    private String buildRequest(String text, String voiceId, float speed) {
        text = text.replaceAll("[\\x00-\\x1F\\x7F-\\x9F]", "").trim();
        if (text.length() > 1000) text = text.substring(0, 1000);

        StringBuilder sb = new StringBuilder();
        sb.append("{\"model\":\"speech-02-hd\",");
        sb.append("\"text\":\"").append(escapeJson(text)).append("\",");
        sb.append("\"stream\":false,");
        sb.append("\"voice_setting\":{");
        sb.append("\"voice_id\":\"").append(voiceId).append("\",");
        sb.append("\"speed\":").append(speed).append(",");
        sb.append("\"pitch\":0,\"volume\":1.0},");
        sb.append("\"audio_setting\":{");
        sb.append("\"audio_format\":\"mp3\",\"sample_rate\":32000}}");
        return sb.toString();
    }

    private String escapeJson(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t");
    }

    private byte[] readAll(java.io.InputStream is) throws Exception {
        java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int len;
        while ((len = is.read(buf)) != -1) bos.write(buf, 0, len);
        return bos.toByteArray();
    }

    private void playAudio(byte[] audio) throws Exception {
        java.io.File tmp = java.io.File.createTempFile("tts_", ".mp3");
        tmp.deleteOnExit();
        java.io.FileOutputStream fos = new java.io.FileOutputStream(tmp);
        fos.write(audio);
        fos.close();

        android.media.MediaPlayer mp = new android.media.MediaPlayer();
        mp.setDataSource(tmp.getAbsolutePath());
        mp.setOnPreparedListener(m -> m.start());
        mp.setOnCompletionListener(android.media.MediaPlayer::release);
        mp.setOnErrorListener((m, w, e) -> { m.release(); tmp.delete(); return true; });
        mp.prepareAsync();
    }
}
