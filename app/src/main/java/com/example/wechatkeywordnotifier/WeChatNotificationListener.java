package com.example.wechatkeywordnotifier;

import android.app.Notification;
import android.app.PendingIntent;
import android.content.Intent;
import android.os.Bundle;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;
import android.speech.tts.TextToSpeech;
import android.util.Log;
import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;
import org.json.JSONArray;
import org.json.JSONObject;
import java.text.SimpleDateFormat;
import java.util.*;

public class WeChatNotificationListener extends NotificationListenerService {
    private static final String TAG = "WeChatNotifier";
    private static final String PREFS_NAME = "WeChatNotifier";
    public static final String KEY_KEYWORDS = "keywords";
    private static final String KEY_HISTORY = "message_history";
    private static final String CHANNEL_ID = "wechat_keyword_alert";
    private static final int MAX_HISTORY = 100;
    private static final String[] DEFAULT_KEYWORDS = {"红黄土", "干杂土", "代运", "放飞", "红砖渣"};

    private TextToSpeech tts;
    private Set<String> keywords = new HashSet<>();

    @Override
    public void onCreate() {
        super.onCreate();
        Log.d(TAG, "Service onCreate");
        tts = new TextToSpeech(this, status -> {
            if (status == TextToSpeech.SUCCESS) {
                tts.setLanguage(Locale.CHINESE);
            }
        });
        // 创建通知渠道
        android.app.NotificationChannel channel = new android.app.NotificationChannel(
                CHANNEL_ID, "关键词提醒", android.app.NotificationManager.IMPORTANCE_HIGH);
        android.app.NotificationManager nm = getSystemService(android.app.NotificationManager.class);
        nm.createNotificationChannel(channel);
    }

    @Override
    public void onNotificationPosted(StatusBarNotification sbn) {
        if (!"com.tencent.mm".equals(sbn.getPackageName())) return;

        loadKeywords();
        if (keywords.isEmpty()) {
            keywords = new HashSet<>(Arrays.asList(DEFAULT_KEYWORDS));
        }

        try {
            Notification notification = sbn.getNotification();
            Bundle extras = notification.extras;
            if (extras == null) return;

            String title = extras.getString(Notification.EXTRA_TITLE, "");
            String text = extras.getString(Notification.EXTRA_TEXT, "");
            String bigText = extras.getString(Notification.EXTRA_BIG_TEXT, "");

            CharSequence[] lines = extras.getCharSequenceArray(Notification.EXTRA_TEXT_LINES);
            StringBuilder fullText = new StringBuilder();
            if (lines != null) {
                for (CharSequence line : lines) {
                    fullText.append(line).append("\n");
                }
            }

            String fullContent = text + " " + bigText + " " + fullText.toString();
            Log.d(TAG, "Title: " + title + " Content: " + fullContent);

            String matchedKeyword = null;
            for (String keyword : keywords) {
                if (fullContent.contains(keyword)) {
                    matchedKeyword = keyword;
                    break;
                }
            }

            if (matchedKeyword != null) {
                String sender = title;
                String groupName = "";
                if (title.contains("(") && title.contains(")")) {
                    int start = title.indexOf("(");
                    int end = title.indexOf(")");
                    groupName = title.substring(0, start);
                    sender = title.substring(start + 1, end);
                }

                saveMessage(matchedKeyword, sender, fullContent.trim(), groupName);

                // 关键：使用原始通知的 PendingIntent，点击后直接跳转到微信对应的聊天
                sendAlertNotification(matchedKeyword, sender, fullContent.trim(), notification);

                // 语音播报
                speakAlert(matchedKeyword, sender);
            }
        } catch (Exception e) {
            Log.e(TAG, "Error processing notification", e);
        }
    }

    /**
     * 发送提醒通知，点击通知后直接飞到微信对应的群/聊天
     * 核心原理：复用微信原始通知中的 PendingIntent（contentIntent）
     */
    private void sendAlertNotification(String keyword, String sender, String content, Notification originalNotification) {
        try {
            NotificationCompat.Builder builder = new NotificationCompat.Builder(this, CHANNEL_ID)
                    .setContentTitle("关键词提醒: " + keyword)
                    .setContentText("来自: " + sender + " | " + content.substring(0, Math.min(content.length(), 50)))
                    .setSmallIcon(android.R.drawable.ic_dialog_alert)
                    .setPriority(NotificationCompat.PRIORITY_HIGH)
                    .setAutoCancel(true);

            // 关键：复用微信原始通知的 PendingIntent
            // 点击我们的通知 -> 直接跳转到微信对应的聊天页面
            PendingIntent originalIntent = originalNotification.contentIntent;
            if (originalIntent != null) {
                builder.setContentIntent(originalIntent);
                Log.d(TAG, "Using original WeChat PendingIntent for click action");
            }

            NotificationManagerCompat.from(this).notify(
                    (int) (System.currentTimeMillis() / 1000), builder.build());
            Log.d(TAG, "Alert notification sent with original PendingIntent");

        } catch (Exception e) {
            Log.e(TAG, "Failed to send alert", e);
        }
    }

    private void loadKeywords() {
        keywords = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                .getStringSet(KEY_KEYWORDS, new HashSet<>());
    }

    private void saveMessage(String keyword, String sender, String content, String group) {
        try {
            String historyJson = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                    .getString(KEY_HISTORY, "[]");
            JSONArray jsonArray = new JSONArray(historyJson);

            JSONObject msg = new JSONObject();
            msg.put("time", new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(new Date()));
            msg.put("keyword", keyword);
            msg.put("sender", sender);
            msg.put("content", content);
            msg.put("group", group);

            JSONArray newArray = new JSONArray();
            newArray.put(msg);
            for (int i = 0; i < jsonArray.length() && i < MAX_HISTORY - 1; i++) {
                newArray.put(jsonArray.getJSONObject(i));
            }

            getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                    .edit()
                    .putString(KEY_HISTORY, newArray.toString())
                    .apply();
        } catch (Exception e) {
            Log.e(TAG, "Save failed", e);
        }
    }

    private void speakAlert(String keyword, String sender) {
        try {
            if (tts != null) {
                String text = "提醒，匹配到关键词" + keyword + "，来自" + sender;
                tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, null);
            }
        } catch (Exception e) {
            Log.e(TAG, "TTS failed", e);
        }
    }

    @Override
    public void onDestroy() {
        if (tts != null) {
            tts.stop();
            tts.shutdown();
        }
        super.onDestroy();
    }
}
