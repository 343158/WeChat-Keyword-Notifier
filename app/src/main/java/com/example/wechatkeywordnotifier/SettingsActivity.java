package com.example.wechatkeywordnotifier;

import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.widget.*;
import androidx.appcompat.app.AppCompatActivity;

public class SettingsActivity extends AppCompatActivity {

    public static final String PREFS_NAME = "WeChatNotifier";

    private Switch swTts;
    private Switch swFloat;
    private TextView tvFloatStatus;
    private Switch swKeepAlive;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);

        swTts = findViewById(R.id.swTts);
        swFloat = findViewById(R.id.swFloat);
        tvFloatStatus = findViewById(R.id.tvFloatStatus);
        swKeepAlive = findViewById(R.id.swKeepAlive);

        loadSettings();

        swTts.setOnCheckedChangeListener((buttonView, isChecked) -> {
            getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                    .edit()
                    .putBoolean("tts_enabled", isChecked)
                    .apply();
        });

        swFloat.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (isChecked) {
                // 检查悬浮窗权限
                if (Build.VERSION.SDK_INT >= 23) {
                    if (!Settings.canDrawOverlays(this)) {
                        // 先请求权限
                        Intent intent = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                Uri.parse("package:" + getPackageName()));
                        startActivity(intent);
                        swFloat.setChecked(false);
                        return;
                    }
                }
                // 有权限，保存设置并启动悬浮窗服务
                getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                        .edit()
                        .putBoolean("float_enabled", true)
                        .apply();
                startService(new Intent(this, FloatingAlertService.class));
            } else {
                getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                        .edit()
                        .putBoolean("float_enabled", false)
                        .apply();
                stopService(new Intent(this, FloatingAlertService.class));
            }
            updateFloatStatus();
        });

        swKeepAlive.setOnCheckedChangeListener((buttonView, isChecked) -> {
            getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                    .edit()
                    .putBoolean("keep_alive_enabled", isChecked)
                    .apply();
            if (isChecked) {
                Intent intent = new Intent(this, KeepAliveService.class);
                if (Build.VERSION.SDK_INT >= 26) {
                    startForegroundService(intent);
                } else {
                    startService(intent);
                }
            } else {
                stopService(new Intent(this, KeepAliveService.class));
            }
        });

        // 关键词铃声 - 打开通知通道设置
        findViewById(R.id.layoutRingtone).setOnClickListener(v -> {
            if (Build.VERSION.SDK_INT >= 26) {
                Intent intent = new Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS);
                intent.putExtra(Settings.EXTRA_APP_PACKAGE, getPackageName());
                intent.putExtra(Settings.EXTRA_CHANNEL_ID, "wechat_keyword_alert");
                startActivity(intent);
            } else {
                // 旧版本直接打开应用通知设置
                Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
                intent.setData(Uri.parse("package:" + getPackageName()));
                startActivity(intent);
            }
        });

        updateFloatStatus();
    }

    @Override
    protected void onResume() {
        super.onResume();
        loadSettings();
        updateFloatStatus();
    }

    private void loadSettings() {
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        swTts.setChecked(prefs.getBoolean("tts_enabled", true));
        swKeepAlive.setChecked(prefs.getBoolean("keep_alive_enabled", false));
        swFloat.setChecked(prefs.getBoolean("float_enabled", false));
    }

    private void updateFloatStatus() {
        if (Build.VERSION.SDK_INT >= 23) {
            boolean hasOverlayPermission = Settings.canDrawOverlays(this);
            if (hasOverlayPermission) {
                tvFloatStatus.setText("已授权");
                tvFloatStatus.setTextColor(0xFF4CAF50);
            } else {
                tvFloatStatus.setText("未授权");
                tvFloatStatus.setTextColor(0xFFFF0000);
            }
        } else {
            tvFloatStatus.setText("");
        }
    }
}
