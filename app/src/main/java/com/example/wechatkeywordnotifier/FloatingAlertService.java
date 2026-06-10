package com.example.wechatkeywordnotifier;

import android.app.Service;
import android.content.Intent;
import android.graphics.PixelFormat;
import android.os.Build;
import android.os.IBinder;
import android.provider.Settings;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.TextView;

public class FloatingAlertService extends Service {

    private WindowManager windowManager;
    private View floatingView;
    private WindowManager.LayoutParams params;

    @Override
    public void onCreate() {
        super.onCreate();
        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && intent.hasExtra("keyword")) {
            String keyword = intent.getStringExtra("keyword");
            String sender = intent.getStringExtra("sender");
            String content = intent.getStringExtra("content");
            showFloatingWindow(keyword, sender, content);
        }
        return START_NOT_STICKY;
    }

    private void showFloatingWindow(String keyword, String sender, String content) {
        if (floatingView != null) {
            windowManager.removeView(floatingView);
            floatingView = null;
        }

        // 检查悬浮窗权限
        if (Build.VERSION.SDK_INT >= 23 && !Settings.canDrawOverlays(this)) {
            stopSelf();
            return;
        }

        floatingView = LayoutInflater.from(this).inflate(R.layout.floating_layout, null);

        TextView titleView = floatingView.findViewById(R.id.floatingTitle);
        TextView contentView = floatingView.findViewById(R.id.floatingContent);
        Button btnView = floatingView.findViewById(R.id.floatingBtnView);
        Button btnClose = floatingView.findViewById(R.id.floatingBtnClose);

        titleView.setText("关键词提醒: " + keyword);
        if (content.length() > 100) {
            content = content.substring(0, 100) + "...";
        }
        contentView.setText("来自: " + sender + "\n" + content);

        btnView.setOnClickListener(v -> {
            // 打开主Activity的消息列表
            Intent intent = new Intent(this, MainActivity.class);
            intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            startActivity(intent);
            dismissFloatingWindow();
        });

        btnClose.setOnClickListener(v -> dismissFloatingWindow());

        int layoutFlag;
        if (Build.VERSION.SDK_INT >= 26) {
            layoutFlag = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY;
        } else {
            layoutFlag = WindowManager.LayoutParams.TYPE_PHONE;
        }

        params = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                layoutFlag,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT);

        params.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
        params.y = 100;

        windowManager.addView(floatingView, params);
    }

    private void dismissFloatingWindow() {
        if (floatingView != null) {
            try {
                windowManager.removeView(floatingView);
            } catch (Exception ignored) {}
            floatingView = null;
        }
        stopSelf();
    }

    @Override
    public void onDestroy() {
        dismissFloatingWindow();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
