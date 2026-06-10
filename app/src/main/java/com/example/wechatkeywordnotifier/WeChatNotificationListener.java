package com.example.wechatkeywordnotifier;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.PixelFormat;
import android.media.AudioAttributes;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.preference.PreferenceManager;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;
import android.speech.tts.TextToSpeech;
import android.util.Log;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.TextView;
import androidx.core.app.NotificationCompat;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

public class WeChatNotificationListener extends NotificationListenerService {
    
    private static final String CHANNEL_ID = "keyword_alert_channel";
    private static final String TAG = "WeChatListener";
    private static final String WECHAT_PACKAGE = "com.tencent.mm";
    private static final String PREFS_HISTORY = "message_history";
    private static final int MAX_HISTORY = 100;
    
    private SharedPreferences prefs;
    private SharedPreferences historyPrefs;
    private Set<String> keywords;
    private TextToSpeech tts;
    private boolean ttsReady = false;
    private WindowManager windowManager;
    private View floatingView;
    
    @Override
    public void onCreate() {
        super.onCreate();
        prefs = PreferenceManager.getDefaultSharedPreferences(this);
        historyPrefs = getSharedPreferences(PREFS_HISTORY, Context.MODE_PRIVATE);
        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
        initTTS();
        loadKeywords();
        createNotificationChannel();
    }
    
    private void initTTS() {
        tts = new TextToSpeech(this, status -> {
            if (status == TextToSpeech.SUCCESS) {
                ttsReady = true;
                tts.setLanguage(Locale.CHINESE);
            }
        });
    }
    
    @Override
    public void onNotificationPosted(StatusBarNotification sbn) {
        if (!WECHAT_PACKAGE.equals(sbn.getPackageName())) {
            return;
        }
        
        loadKeywords();
        
        Bundle extras = sbn.getNotification().extras;
        if (extras == null) return;
        
        String title = extras.getString(Notification.EXTRA_TITLE, "");
        String text = extras.getString(Notification.EXTRA_TEXT, "");
        String bigText = extras.getString(Notification.EXTRA_BIG_TEXT, "");
        
        StringBuilder fullText = new StringBuilder();
        if (!title.isEmpty()) fullText.append(title).append(" ");
        if (!text.isEmpty()) fullText.append(text).append(" ");
        if (!bigText.isEmpty()) fullText.append(bigText);
        
        String content = fullText.toString();
        Log.d(TAG, "收到微信通知: " + content);
        
        for (String keyword : keywords) {
            if (content.contains(keyword)) {
                Log.d(TAG, "匹配到关键词: " + keyword);
                long timestamp = System.currentTimeMillis();
                saveToHistory(title, text, keyword, timestamp);
                sendAlert(title, content, keyword, timestamp);
                speakAlert(keyword, title, text);
                showFloatingWindow(title, text, keyword);
                break;
            }
        }
    }
    
    private void saveToHistory(String title, String content, String keyword, long timestamp) {
        try {
            JSONArray history = new JSONArray(historyPrefs.getString("history", "[]"));
            
            JSONObject entry = new JSONObject();
            entry.put("title", title);
            entry.put("content", content);
            entry.put("keyword", keyword);
            entry.put("timestamp", timestamp);
            entry.put("time", new SimpleDateFormat("MM-dd HH:mm", Locale.CHINA).format(new Date(timestamp)));
            
            history.put(entry);
            
            // Keep only last MAX_HISTORY entries
            if (history.length() > MAX_HISTORY) {
                JSONArray trimmed = new JSONArray();
                for (int i = history.length() - MAX_HISTORY; i < history.length(); i++) {
                    trimmed.put(history.get(i));
                }
                history = trimmed;
            }
            
            historyPrefs.edit().putString("history", history.toString()).apply();
        } catch (Exception e) {
            Log.e(TAG, "保存历史失败", e);
        }
    }
    
    private void sendAlert(String title, String content, String keyword, long timestamp) {
        Intent intent = new Intent(this, HistoryActivity.class);
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent pendingIntent = PendingIntent.getActivity(this, (int) timestamp, intent, 
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        
        NotificationCompat.Builder builder = new NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("⚠️ 关键词提醒: " + keyword)
            .setContentText(title + ": " + content)
            .setStyle(new NotificationCompat.BigTextStyle()
                .bigText("【" + keyword + "】\n来自: " + title + "\n内容: " + content))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .setVibrate(new long[]{0, 500, 200, 500});
        
        NotificationManager manager = getSystemService(NotificationManager.class);
        manager.notify((int) timestamp, builder.build());
    }
    
    private void speakAlert(String keyword, String title, String content) {
        if (ttsReady && tts != null) {
            String message = "提醒，匹配到关键词" + keyword + "，来自" + title;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                tts.speak(message, TextToSpeech.QUEUE_FLUSH, null, "keyword_alert");
            } else {
                tts.speak(message, TextToSpeech.QUEUE_FLUSH, null);
            }
        }
    }
    
    private void showFloatingWindow(String title, String content, String keyword) {
        if (floatingView != null) {
            removeFloatingWindow();
        }
        
        LayoutInflater inflater = LayoutInflater.from(this);
        floatingView = inflater.inflate(R.layout.floating_alert, null);
        
        TextView tvTitle = floatingView.findViewById(R.id.tv_alert_title);
        TextView tvContent = floatingView.findViewById(R.id.tv_alert_content);
        TextView tvKeyword = floatingView.findViewById(R.id.tv_alert_keyword);
        Button btnClose = floatingView.findViewById(R.id.btn_close);
        Button btnHistory = floatingView.findViewById(R.id.btn_history);
        
        tvTitle.setText(title);
        tvContent.setText(content);
        tvKeyword.setText("匹配: " + keyword);
        
        btnClose.setOnClickListener(v -> removeFloatingWindow());
        btnHistory.setOnClickListener(v -> {
            removeFloatingWindow();
            Intent intent = new Intent(this, HistoryActivity.class);
            intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(intent);
        });
        
        WindowManager.LayoutParams params = new WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.O 
                ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY 
                : WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON,
            PixelFormat.TRANSLUCENT
        );
        params.gravity = Gravity.TOP;
        params.y = 100;
        
        try {
            windowManager.addView(floatingView, params);
            // Auto remove after 10 seconds
            new Handler(Looper.getMainLooper()).postDelayed(this::removeFloatingWindow, 10000);
        } catch (Exception e) {
            Log.e(TAG, "悬浮窗显示失败", e);
        }
    }
    
    private void removeFloatingWindow() {
        if (floatingView != null) {
            try {
                windowManager.removeView(floatingView);
            } catch (Exception e) {
                Log.e(TAG, "移除悬浮窗失败", e);
            }
            floatingView = null;
        }
    }
    
    @Override
    public void onNotificationRemoved(StatusBarNotification sbn) {
    }
    
    private void loadKeywords() {
        keywords = prefs.getStringSet(MainActivity.KEY_KEYWORDS, new HashSet<>());
        if (keywords.isEmpty()) {
            keywords = new HashSet<>();
            keywords.add("红黄土");
            keywords.add("干杂土");
            keywords.add("代运");
            keywords.add("放飞");
            keywords.add("红砖渣");
            prefs.edit().putStringSet(MainActivity.KEY_KEYWORDS, keywords).apply();
        }
    }
    
    private void createNotificationChannel() {
        NotificationChannel channel = new NotificationChannel(
            CHANNEL_ID,
            "关键词提醒",
            NotificationManager.IMPORTANCE_HIGH
        );
        channel.setDescription("微信关键词匹配提醒");
        channel.enableVibration(true);
        
        AudioAttributes audioAttributes = new AudioAttributes.Builder()
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .setUsage(AudioAttributes.USAGE_NOTIFICATION)
            .build();
        channel.setSound(android.provider.Settings.System.DEFAULT_NOTIFICATION_URI, audioAttributes);
        
        NotificationManager manager = getSystemService(NotificationManager.class);
        manager.createNotificationChannel(channel);
    }
    
    @Override
    public void onDestroy() {
        super.onDestroy();
        removeFloatingWindow();
        if (tts != null) {
            tts.stop();
            tts.shutdown();
        }
    }
}