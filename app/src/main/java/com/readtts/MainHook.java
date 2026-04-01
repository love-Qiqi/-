package com.readtts;

import static de.robv.android.xposed.XposedHelpers.*;

import android.util.Log;
import android.content.Context;
import android.content.SharedPreferences;
import android.security.EncryptedSharedPreferences;
import android.security.MainKey;
import android.net.Uri;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import de.robv.android.xposed.*;
import de.robv.android.xposed.callbacks.*;
import dalvik.system.BaseDexClassLoader;

/**
 * ReadTTS - MiniMax TTS Hook for Reading App
 * Hook阅读App的TTS模块，替换为MiniMax TTS
 */
public class MainHook implements IXposedHookLoadPackage {

    private static final String TAG = "ReadTTS";
    private static final String TARGET_PACKAGE = "com.chaoxning.reading";
    private static final String TTS_API_URL = "https://api.minimax.chat/v1/t2a_v2";

    private String apiKey = "";
    private String voiceId = "female-tianmei";
    private float speed = 1.0f;
    private boolean enabled = true;

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) throws Throwable {
        if (!lpparam.packageName.equals(TARGET_PACKAGE)) return;

        Log.d(TAG, "Hooked Reading App: " + lpparam.packageName);

        // Load user settings
        loadSettings(lpparam);

        // Hook TtsManager
        hookTtsManager(lpparam);

        // Hook TTS playback
        hookTtsPlayback(lpparam);

        Log.d(TAG, "ReadTTS hooked successfully!");
    }

    private void loadSettings(XC_LoadPackage.LoadPackageParam lpparam) {
        try {
            Context ctx = (Context) callMethod(lpparam.appInfo, "makeApplication", false, null);
            if (ctx == null) {
                // Try to get the app's context another way
                Object loadedApk = getObjectField(lpparam.classLoader, "pkg");
                if (loadedApk != null) {
                    Context appContext = (Context) callMethod(loadedApk, "getPackage", 0);
                    if (appContext != null) {
                        SharedPreferences sp = appContext.getSharedPreferences("readtts_config", Context.MODE_PRIVATE);
                        apiKey = sp.getString("api_key", "");
                        voiceId = sp.getString("voice_id", "female-tianmei");
                        speed = sp.getFloat("speed", 1.0f);
                        enabled = sp.getBoolean("enabled", true);
                        Log.d(TAG, "Settings loaded from SharedPreferences");
                    }
                }
            } else {
                SharedPreferences sp = ctx.getSharedPreferences("readtts_config", Context.MODE_PRIVATE);
                apiKey = sp.getString("api_key", "");
                voiceId = sp.getString("voice_id", "female-tianmei");
                speed = sp.getFloat("speed", 1.0f);
                enabled = sp.getBoolean("enabled", true);
                Log.d(TAG, "Settings loaded from context");
            }
        } catch (Throwable t) {
            Log.e(TAG, "Failed to load settings: " + t.getMessage());
        }
    }

    private void hookTtsManager(XC_LoadPackage.LoadPackageParam lpparam) {
        try {
            Class<?> ttsManagerClass = lpparam.classLoader.loadClass("com.chaoxning.tts.TtsManager");
            Log.d(TAG, "Found TtsManager: " + ttsManagerClass.getName());

            // Hook speak() method
            findAndHookMethod(ttsManagerClass, "speak", String.class, int.class, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                    if (!enabled || apiKey.isEmpty()) return;
                    String text = (String) param.args[0];
                    if (text == null || text.isEmpty()) return;
                    Log.d(TAG, "Intercepted speak: " + text.substring(0, Math.min(20, text.length())));

                    // Cancel original TTS
                    param.setResult(null);

                    // Play with MiniMax TTS
                    playMiniMaxTTS(text, (Integer) param.args[1]);
                }

                @Override
                protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                    // Do nothing, we handle it in beforeHookedMethod
                }
            });

            // Hook startTts()
            try {
                findAndHookMethod(ttsManagerClass, "startTts", String.class, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                        if (!enabled || apiKey.isEmpty()) return;
                        String text = (String) param.args[0];
                        if (text == null || text.isEmpty()) return;
                        Log.d(TAG, "Intercepted startTts: " + text.substring(0, Math.min(20, text.length())));
                        param.setResult(null);
                        playMiniMaxTTS(text, 0);
                    }
                });
            } catch (Throwable t) {
                Log.d(TAG, "startTts method not found, skipping");
            }

            // Hook playTts()
            try {
                findAndHookMethod(ttsManagerClass, "playTts", String.class, int.class, int.class, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                        if (!enabled || apiKey.isEmpty()) return;
                        String text = (String) param.args[0];
                        if (text == null || text.isEmpty()) return;
                        Log.d(TAG, "Intercepted playTts: " + text.substring(0, Math.min(20, text.length())));
                        param.setResult(null);
                        int queueMode = param.args.length > 1 ? (Integer) param.args[1] : 0;
                        playMiniMaxTTS(text, queueMode);
                    }
                });
            } catch (Throwable t) {
                Log.d(TAG, "playTts method not found, skipping");
            }

        } catch (ClassNotFoundException e) {
            Log.e(TAG, "TtsManager class not found, trying alternative: " + e.getMessage());
            // Try alternative class names
            hookAlternativeTTS(lpparam);
        } catch (Throwable t) {
            Log.e(TAG, "Failed to hook TtsManager: " + t.getMessage());
        }
    }

    private void hookAlternativeTTS(XC_LoadPackage.LoadPackageParam lpparam) {
        String[] alternativeClasses = {
            "com.chaoxning.tts.TtsHelper",
            "com.chaoxning.tts.TtsPlayer",
            "com.chaoxning.tts.TtsEngine",
            "com.chaoxning.tts.SpeechSynthesizer",
            "com.chaoxning.tts.TtsImpl"
        };

        for (String className : alternativeClasses) {
            try {
                Class<?> cls = lpparam.classLoader.loadClass(className);
                Log.d(TAG, "Found alternative TTS class: " + className);

                // Hook all public methods that take String
                for (java.lang.reflect.Method m : cls.getDeclaredMethods()) {
                    if (m.getParameterCount() >= 1 && m.getParameterTypes()[0] == String.class) {
                        String methodName = m.getName();
                        Class<?>[] paramTypes = m.getParameterTypes();

                        if (paramTypes.length == 1) {
                            findAndHookMethod(cls, methodName, paramTypes[0], new XC_MethodHook() {
                                @Override
                                protected void beforeHookedMethod(MethodHookParam hparam) throws Throwable {
                                    if (!enabled || apiKey.isEmpty()) return;
                                    String text = (String) hparam.args[0];
                                    if (text != null && !text.isEmpty()) {
                                        Log.d(TAG, "Intercepted " + methodName + ": " + text.substring(0, Math.min(20, text.length())));
                                        hparam.setResult(null);
                                        playMiniMaxTTS(text, 0);
                                    }
                                }
                            });
                        } else if (paramTypes.length >= 2 && paramTypes[1] == int.class) {
                            findAndHookMethod(cls, methodName, paramTypes[0], paramTypes[1], new XC_MethodHook() {
                                @Override
                                protected void beforeHookedMethod(MethodHookParam hparam) throws Throwable {
                                    if (!enabled || apiKey.isEmpty()) return;
                                    String text = (String) hparam.args[0];
                                    if (text != null && !text.isEmpty()) {
                                        Log.d(TAG, "Intercepted " + methodName + ": " + text.substring(0, Math.min(20, text.length())));
                                        hparam.setResult(null);
                                        playMiniMaxTTS(text, (Integer) hparam.args[1]);
                                    }
                                }
                            });
                        }
                    }
                }
                Log.d(TAG, "Successfully hooked alternative TTS: " + className);
                break;
            } catch (Throwable t) {
                // Try next class
            }
        }
    }

    private void hookTtsPlayback(XC_LoadPackage.LoadPackageParam lpparam) {
        // Hook the system TextToSpeech to intercept playback
        try {
            Class<?> ttsClass = lpparam.classLoader.loadClass("android.speech.tts.TextToSpeech");
            findAndHookMethod(ttsClass, "speak", String.class, int.class, android.os.Bundle.class, String.class,
                new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                        if (!enabled || apiKey.isEmpty()) return;
                        String text = (String) param.args[0];
                        if (text == null || text.isEmpty()) return;
                        if (text.contains("__MINIMAX_TTS__")) return; // Prevent recursion

                        Log.d(TAG, "Intercepted system TTS speak: " + text.substring(0, Math.min(20, text.length())));
                        param.setResult(0);
                        playMiniMaxTTS(text, (Integer) param.args[1]);
                    }
                });
        } catch (Throwable t) {
            Log.d(TAG, "System TTS hook not applicable for this context");
        }
    }

    private void playMiniMaxTTS(String text, int queueMode) {
        new Thread(() -> {
            try {
                // Build request
                String jsonBody = buildTTSRequest(text);
                java.net.URL url = new java.net.URL(TTS_API_URL);
                java.net.HttpURLConnection conn = (java.net.HttpURLConnection) url.openConnection();

                conn.setRequestMethod("POST");
                conn.setRequestProperty("Authorization", "Bearer " + apiKey);
                conn.setRequestProperty("Content-Type", "application/json");
                conn.setDoOutput(true);
                conn.setConnectTimeout(10000);
                conn.setReadTimeout(30000);

                // Write body
                OutputStream os = conn.getOutputStream();
                os.write(jsonBody.getBytes("UTF-8"));
                os.close();

                int responseCode = conn.getResponseCode();
                if (responseCode == 200) {
                    InputStream is = conn.getInputStream();
                    byte[] audioData = readAllBytes(is);
                    is.close();

                    Log.d(TAG, "MiniMax TTS success, audio size: " + audioData.length + " bytes");

                    // Play audio using system MediaPlayer
                    playAudio(audioData, queueMode);

                } else {
                    String error = readError(conn);
                    Log.e(TAG, "MiniMax TTS failed: " + responseCode + " - " + error);
                }

                conn.disconnect();

            } catch (Throwable t) {
                Log.e(TAG, "MiniMax TTS error: " + t.getMessage());
                t.printStackTrace();
            }
        }).start();
    }

    private String buildTTSRequest(String text) {
        // Clean text - remove special reading app markers
        text = text.replaceAll("[\u0000-\u001F\u007F-\u009F]", "").trim();
        if (text.length() > 1000) {
            text = text.substring(0, 1000); // MiniMax limit
        }

        return "{"
            + "\"model\":\"speech-02-hd\","
            + "\"text\":\"" + escapeJson(text) + "\","
            + "\"stream\":false,"
            + "\"voice_setting\":{"
            + "\"voice_id\":\"" + voiceId + "\","
            + "\"speed\":" + speed + ","
            + "\"pitch\":0,"
            + "\"volume\":1.0},"
            + "\"audio_setting\":{"
            + "\"audio_format\":\"mp3\","
            + "\"sample_rate\":32000}}";
    }

    private void playAudio(byte[] audioData, int queueMode) throws Throwable {
        // Save to temp file
        File tempFile = File.createTempFile("tts_", ".mp3");
        tempFile.deleteOnExit();
        FileOutputStream fos = new FileOutputStream(tempFile);
        fos.write(audioData);
        fos.close();

        final android.media.MediaPlayer mp = new android.media.MediaPlayer();
        mp.setDataSource(tempFile.getAbsolutePath());
        mp.setOnPreparedListener(mp1 -> {
            mp1.start();
            mp1.setOnCompletionListener(mp2 -> {
                mp2.release();
                tempFile.delete();
            });
        });
        mp.setOnErrorListener((mp1, what, extra) -> {
            mp1.release();
            tempFile.delete();
            return true;
        });
        mp.prepareAsync();
    }

    private byte[] readAllBytes(InputStream is) throws Exception {
        java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int len;
        while ((len = is.read(buf)) != -1) {
            bos.write(buf, 0, len);
        }
        return bos.toByteArray();
    }

    private String readError(java.net.HttpURLConnection conn) {
        try {
            InputStream is = conn.getErrorStream();
            if (is != null) {
                byte[] data = readAllBytes(is);
                return new String(data, "UTF-8");
            }
        } catch (Throwable t) {}
        return "unknown error";
    }

    private String escapeJson(String s) {
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }
}
