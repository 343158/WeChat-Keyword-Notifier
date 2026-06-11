package com.example.wechatkeywordnotifier;

import android.app.Notification;
import android.app.PendingIntent;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
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
    private static final int MAX_HISTORY = 200;
    private static final String[] DEFAULT_KEYWORDS = {"红黄土", "干杂土", "代运", "放飞", "红砖渣"};

    private TextToSpeech tts;
    private Set<String> keywords = new HashSet<>();
    private boolean ttsEnabled = true;
    private boolean floatEnabled = false;

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
        if (Build.VERSION.SDK_INT >= 26) {
            android.app.NotificationChannel channel = new android.app.NotificationChannel(
                    CHANNEL_ID, "拾微", android.app.NotificationManager.IMPORTANCE_HIGH);
            android.app.NotificationManager nm = getSystemService(android.app.NotificationManager.class);
            nm.createNotificationChannel(channel);
        }

        loadPreferences();
    }

    @Override
    public void onNotificationPosted(StatusBarNotification sbn) {
        if (!"com.tencent.mm".equals(sbn.getPackageName())) return;

        loadKeywords();
        if (keywords.isEmpty()) {
            keywords = new HashSet<>(Arrays.asList(DEFAULT_KEYWORDS));
        }

        loadPreferences();

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

                String messageKey = saveMessage(matchedKeyword, sender, fullContent.trim(), groupName);

                // 缓存原始通知的 PendingIntent，供 MainActivity 跳转聊天使用
                PendingIntent originalIntent = notification.contentIntent;
                if (originalIntent != null && messageKey != null) {
                    MainActivity.pendingIntentCache.put(messageKey, originalIntent);
                    Log.d(TAG, "Cached PendingIntent for key: " + messageKey);
                }

                // 发送提醒通知（点击后跳转微信聊天）
                sendAlertNotification(matchedKeyword, sender, fullContent.trim(), notification);

                // 语音播报
                speakAlert(matchedKeyword, sender);

                // 悬浮窗提醒
                if (floatEnabled) {
                    showFloatingAlert(matchedKeyword, sender, fullContent.trim());
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Error processing notification", e);
        }
    }

    /**
     * 发送提醒通知，点击后跳转到微信对应聊天
     */
    private void sendAlertNotification(String keyword, String sender, String content, Notification originalNotification) {
        try {
            NotificationCompat.Builder builder = new NotificationCompat.Builder(this, CHANNEL_ID)
                    .setContentTitle("拾微: " + keyword)
                    .setContentText("来自: " + sender + " | " + content.substring(0, Math.min(content.length(), 50)))
                    .setSmallIcon(android.R.drawable.ic_dialog_alert)
                    .setPriority(NotificationCompat.PRIORITY_HIGH)
                    .setAutoCancel(true);

            // 复用微信原始通知的 PendingIntent → 点击后直接跳到微信对应聊天
            PendingIntent originalIntent = originalNotification.contentIntent;
            if (originalIntent != null) {
                builder.setContentIntent(originalIntent);
                Log.d(TAG, "Using original WeChat PendingIntent");
            }

            NotificationManagerCompat.from(this).notify(
                    (int) (System.currentTimeMillis() / 1000), builder.build());

        } catch (Exception e) {
            Log.e(TAG, "Failed to send alert", e);
        }
    }

    private void loadKeywords() {
        keywords = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                .getStringSet(KEY_KEYWORDS, new HashSet<>());
    }

    private void loadPreferences() {
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        ttsEnabled = prefs.getBoolean("tts_enabled", true);
        floatEnabled = prefs.getBoolean("float_enabled", false);
    }

    /**
     * 保存消息到历史记录，返回 messageKey
     */
    private String saveMessage(String keyword, String sender, String content, String group) {
        try {
            String historyJson = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                    .getString(KEY_HISTORY, "[]");
            JSONArray jsonArray = new JSONArray(historyJson);

            String now = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(new Date());

            JSONObject msg = new JSONObject();
            msg.put("time", now);
            msg.put("keyword", keyword);
            msg.put("sender", sender);
            msg.put("content", content);
            msg.put("group", group);

            // 生成唯一键用于 PendingIntent 缓存
            String messageKey = now + "_" + keyword + "_" + sender.hashCode();
            msg.put("messageKey", messageKey);

            JSONArray newArray = new JSONArray();
            newArray.put(msg);
            for (int i = 0; i < jsonArray.length() && i < MAX_HISTORY - 1; i++) {
                newArray.put(jsonArray.getJSONObject(i));
            }

            getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                    .edit()
                    .putString(KEY_HISTORY, newArray.toString())
                    .apply();

            return messageKey;

        } catch (Exception e) {
            Log.e(TAG, "Save failed", e);
            return null;
        }
    }

    private void speakAlert(String keyword, String sender) {
        if (!ttsEnabled) return;
        try {
            if (tts != null) {
                String text = "提醒，匹配到关键词" + keyword + "，来自" + sender;
                tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, null);
            }
        } catch (Exception e) {
            Log.e(TAG, "TTS failed", e);
        }
    }

    private void showFloatingAlert(String keyword, String sender, String content) {
        try {
            Intent intent = new Intent(this, FloatingAlertService.class);
            intent.putExtra("keyword", keyword);
            intent.putExtra("sender", sender);
            intent.putExtra("content", content);
            if (Build.VERSION.SDK_INT >= 26) {
                startForegroundService(intent);
            } else {
                startService(intent);
            }
        } catch (Exception e) {
            Log.e(TAG, "Floating alert failed", e);
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
