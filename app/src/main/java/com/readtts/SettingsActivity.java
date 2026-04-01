package com.readtts;

import android.app.Activity;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.RadioGroup;
import android.widget.TextView;
import android.widget.Toast;
import android.os.Looper;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class SettingsActivity extends Activity {

    private EditText apiKeyInput;
    private RadioGroup voiceGroup;
    private EditText speedInput;
    private TextView statusText;

    private static final String PREF_NAME = "readtts_config";

    // MiniMax voice options
    private String[] voiceIds = {
        "female-tianmei",    // 女声甜美
        "male-qn_qingse",    // 男声清晰
        "female-yunyang",    // 女声云扬
        "male-zhouzhao",     // 男声周昭
        "female-shawn"       // 女声Shawn
    };

    private String[] voiceNames = {
        "女声-甜美",
        "男声-清晰",
        "女声-云扬",
        "男声-周昭",
        "女声-Shawn"
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);

        apiKeyInput = findViewById(R.id.edit_api_key);
        voiceGroup = findViewById(R.id.voice_group);
        speedInput = findViewById(R.id.edit_speed);
        statusText = findViewById(R.id.text_status);
        Button testBtn = findViewById(R.id.btn_test);
        Button saveBtn = findViewById(R.id.btn_save);

        // Load saved settings
        loadSettings();

        // Test TTS
        testBtn.setOnClickListener(v -> testTTS());

        // Save
        saveBtn.setOnClickListener(v -> saveSettings());
    }

    private void loadSettings() {
        SharedPreferences sp = getSharedPreferences(PREF_NAME, MODE_PRIVATE);
        String apiKey = sp.getString("api_key", "");
        String voiceId = sp.getString("voice_id", "female-tianmei");
        float speed = sp.getFloat("speed", 1.0f);

        apiKeyInput.setText(apiKey);
        speedInput.setText(String.valueOf(speed));

        // Select voice
        for (int i = 0; i < voiceIds.length; i++) {
            if (voiceIds[i].equals(voiceId)) {
                voiceGroup.check(voiceGroup.getChildAt(i).getId());
                break;
            }
        }
    }

    private void saveSettings() {
        String apiKey = apiKeyInput.getText().toString().trim();
        float speed;
        try {
            speed = Float.parseFloat(speedInput.getText().toString().trim());
            if (speed < 0.5f || speed > 2.0f) {
                Toast.makeText(this, "语速建议在0.5-2.0之间", Toast.LENGTH_SHORT).show();
                return;
            }
        } catch (NumberFormatException e) {
            Toast.makeText(this, "请输入有效数字", Toast.LENGTH_SHORT).show();
            return;
        }

        int selectedId = voiceGroup.getCheckedRadioButtonId();
        String voiceId = "female-tianmei";
        for (int i = 0; i < voiceGroup.getChildCount(); i++) {
            if (voiceGroup.getChildAt(i).getId() == selectedId) {
                voiceId = voiceIds[i];
                break;
            }
        }

        SharedPreferences sp = getSharedPreferences(PREF_NAME, MODE_PRIVATE);
        SharedPreferences.Editor editor = sp.edit();
        editor.putString("api_key", apiKey);
        editor.putString("voice_id", voiceId);
        editor.putFloat("speed", speed);
        editor.putBoolean("enabled", true);
        editor.apply();

        Toast.makeText(this, "设置已保存，请重启阅读App后生效", Toast.LENGTH_LONG).show();
    }

    private void testTTS() {
        String apiKey = apiKeyInput.getText().toString().trim();
        if (apiKey.isEmpty()) {
            Toast.makeText(this, "请先输入API Key", Toast.LENGTH_SHORT).show();
            return;
        }

        statusText.setText("正在测试...");
        testBtn.setEnabled(false);

        int selectedId = voiceGroup.getCheckedRadioButtonId();
        String voiceId = "female-tianmei";
        for (int i = 0; i < voiceGroup.getChildCount(); i++) {
            if (voiceGroup.getChildAt(i).getId() == selectedId) {
                voiceId = voiceIds[i];
                break;
            }
        }

        float speed;
        try {
            speed = Float.parseFloat(speedInput.getText().toString().trim());
        } catch (NumberFormatException e) {
            speed = 1.0f;
        }

        ExecutorService executor = Executors.newSingleThreadExecutor();
        executor.execute(() -> {
            try {
                String testText = "这是一段测试文本，朗读功能正在工作。";
                byte[] audioData = MiniMaxTTSClient.synthesize(apiKey, testText, voiceId, speed);

                if (audioData != null && audioData.length > 0) {
                    runOnUiThread(() -> {
                        statusText.setText("测试成功！正在播放...");
                        android.media.MediaPlayer mp = new android.media.MediaPlayer();
                        try {
                            java.io.File tempFile = java.io.File.createTempFile("tts_test_", ".mp3");
                            tempFile.deleteOnExit();
                            java.io.FileOutputStream fos = new java.io.FileOutputStream(tempFile);
                            fos.write(audioData);
                            fos.close();
                            mp.setDataSource(tempFile.getAbsolutePath());
                            mp.setOnPreparedListener(p -> {
                                p.start();
                                statusText.setText("播放成功！");
                            });
                            mp.setOnCompletionListener(p -> {
                                p.release();
                                tempFile.delete();
                                statusText.setText("测试完成");
                            });
                            mp.setOnErrorListener((p, what, extra) -> {
                                statusText.setText("播放失败: " + what);
                                return true;
                            });
                            mp.prepareAsync();
                        } catch (Exception e) {
                            statusText.setText("播放失败: " + e.getMessage());
                        }
                    });
                } else {
                    runOnUiThread(() -> statusText.setText("测试失败: 无音频数据"));
                }
            } catch (Exception e) {
                runOnUiThread(() -> statusText.setText("错误: " + e.getMessage()));
            } finally {
                runOnUiThread(() -> testBtn.setEnabled(true));
            }
        });
    }
}
