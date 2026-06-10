package com.example.wechatkeywordnotifier;

import android.app.Notification;
import android.os.Bundle;
import android.os.PowerManager;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;
import android.speech.tts.TextToSpeech;
import android.util.Log;
import androidx.core.app.NotificationCompat;
import org.json.JSONArray;
import org.json.JSONObject;
import java.text.SimpleDateFormat;
import java.util.*;

public class WeChatNotificationListener extends NotificationListenerService {
    private static final String TAG = "WeChatNotifier";
    private static final String PREFS_NAME = "WeChatNotifier";
    private static final String KEY_KEYWORDS = "keywords";
    private static final String KEY_HISTORY = "message_history";
    private static final int MAX_HISTORY = 100;

    private TextToSpeech tts;
    private Set<String> keywords = new HashSet<>();
    private static final String[] DEFAULT_KEYWORDS = {"红黄土", "干杂土", "代运", "放飞", "红砖渣"};

    @Override
    public void onCreate() {
        super.onCreate();
        Log.d(TAG, "Service onCreate");
        
        // 初始化 TTS
        tts = new TextToSpeech(this, status -> {
            if (status == TextToSpeech.SUCCESS) {
                tts.setLanguage(Locale.CHINESE);
                Log.d(TAG, "TTS 初始化成功");
            }
        });
    }

    @Override
    public void onNotificationPosted(StatusBarNotification sbn) {
        Log.d(TAG, "收到通知: " + sbn.getPackageName());
        
        // 只处理微信通知
        if (!"com.tencent.mm".equals(sbn.getPackageName())) {
            return;
        }

        // 加载关键词
        loadKeywords();
        if (keywords.isEmpty()) {
            Log.d(TAG, "关键词列表为空，使用默认关键词");
            keywords = new HashSet<>(Arrays.asList(DEFAULT_KEYWORDS));
        }

        try {
            Notification notification = sbn.getNotification();
            Bundle extras = notification.extras;
            
            if (extras == null) {
                Log.d(TAG, "Notification extras 为空");
                return;
            }

            // 获取通知标题和内容
            String title = extras.getString(Notification.EXTRA_TITLE, "");
            String text = extras.getString(Notification.EXTRA_TEXT, "");
            String bigText = extras.getString(Notification.EXTRA_BIG_TEXT, "");
            
            // 尝试获取更多内容
            CharSequence[] lines = extras.getCharSequenceArray(Notification.EXTRA_TEXT_LINES);
            StringBuilder fullText = new StringBuilder();
            if (lines != null) {
                for (CharSequence line : lines) {
                    fullText.append(line).append("\n");
                }
            }
            
            // 组合所有文本内容
            String fullContent = text + " " + bigText + " " + fullText.toString();
            
            Log.d(TAG, "标题: " + title);
            Log.d(TAG, "内容: " + fullContent);

            // 检查是否包含关键词
            String matchedKeyword = null;
            for (String keyword : keywords) {
                if (fullContent.contains(keyword)) {
                    matchedKeyword = keyword;
                    break;
                }
            }

            if (matchedKeyword != null) {
                Log.d(TAG, "匹配到关键词: " + matchedKeyword);
                
                // 提取发送者名字
                String sender = title;
                String groupName = "";
                
                // 判断是群消息还是私聊
                if (title.contains("(") && title.contains(")")) {
                    // 群消息格式: 群名(发送者)
                    int start = title.indexOf("(");
                    int end = title.indexOf(")");
                    groupName = title.substring(0, start);
                    sender = title.substring(start + 1, end);
                }
                
                // 保存消息到历史记录
                saveMessage(matchedKeyword, sender, fullContent.trim(), groupName);
                
                // 发送系统通知
                sendAlertNotification(matchedKeyword, sender, fullContent.trim());
                
                // 显示悬浮窗
                showFloatingAlert(matchedKeyword, sender, fullContent.trim());
                
                // 语音播报
                speakAlert(matchedKeyword, sender);
                
            } else {
                Log.d(TAG, "未匹配到关键词");
            }

        } catch (Exception e) {
            Log.e(TAG, "处理通知时出错", e);
        }
    }

    private void loadKeywords() {
        keywords = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                .getStringSet(KEY_KEYWORDS, new HashSet<>());
        Log.d(TAG, "加载关键词: " + keywords);
    }

    private void saveMessage(String keyword, String sender, String content, String group) {
        try {
            // 获取现有历史记录
            String historyJson = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                    .getString(KEY_HISTORY, "[]");
            JSONArray jsonArray = new JSONArray(historyJson);
            
            // 创建新消息记录
            JSONObject msg = new JSONObject();
            msg.put("time", new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(new Date()));
            msg.put("keyword", keyword);
            msg.put("sender", sender);
            msg.put("content", content);
            msg.put("group", group);
            
            // 添加到历史记录开头
            JSONArray newArray = new JSONArray();
            newArray.put(msg);
            for (int i = 0; i < jsonArray.length() && i < MAX_HISTORY - 1; i++) {
                newArray.put(jsonArray.getJSONObject(i));
            }
            
            // 保存
            getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                    .edit()
                    .putString(KEY_HISTORY, newArray.toString())
                    .apply();
            
            Log.d(TAG, "消息已保存到历史记录");
            
        } catch (Exception e) {
            Log.e(TAG, "保存消息失败", e);
        }
    }

    private void sendAlertNotification(String keyword, String sender, String content) {
        try {
            String channelId = "wechat_keyword_alert";
            
            NotificationCompat.Builder builder = new NotificationCompat.Builder(this, channelId)
                    .setContentTitle("🔔 关键词提醒")
                    .setContentText("匹配到: " + keyword + " | 来自: " + sender)
                    .setSmallIcon(R.drawable.ic_launcher_foreground)
                    .setPriority(NotificationCompat.PRIORITY_HIGH)
                    .setAutoCancel(true);
            
            android.app.NotificationManager nm = getSystemService(android.app.NotificationManager.class);
            nm.notify((int) System.currentTimeMillis(), builder.build());
            
            Log.d(TAG, "系统通知已发送");
            
        } catch (Exception e) {
            Log.e(TAG, "发送通知失败", e);
        }
    }

    private void showFloatingAlert(String keyword, String sender, String content) {
        try {
            Intent intent = new Intent(this, FloatingAlertService.class);
            intent.putExtra("keyword", keyword);
            intent.putExtra("sender", sender);
            intent.putExtra("content", content);
            startService(intent);
            Log.d(TAG, "悬浮窗服务已启动");
        } catch (Exception e) {
            Log.e(TAG, "启动悬浮窗失败", e);
        }
    }

    private void speakAlert(String keyword, String sender) {
        try {
            if (tts != null) {
                String text = "提醒，匹配到关键词" + keyword + "，来自" + sender;
                tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, null);
                Log.d(TAG, "语音播报: " + text);
            }
        } catch (Exception e) {
            Log.e(TAG, "语音播报失败", e);
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
