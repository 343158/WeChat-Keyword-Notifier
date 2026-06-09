package com.example.wechatkeywordnotifier;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.preference.PreferenceManager;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;
import android.util.Log;
import androidx.core.app.NotificationCompat;
import java.util.HashSet;
import java.util.Set;

public class WeChatNotificationListener extends NotificationListenerService {
    
    private static final String CHANNEL_ID = "keyword_alert_channel";
    private static final String TAG = "WeChatListener";
    private static final String WECHAT_PACKAGE = "com.tencent.mm";
    
    private SharedPreferences prefs;
    private Set<String> keywords;
    
    @Override
    public void onCreate() {
        super.onCreate();
        prefs = PreferenceManager.getDefaultSharedPreferences(this);
        loadKeywords();
        createNotificationChannel();
    }
    
    @Override
    public void onNotificationPosted(StatusBarNotification sbn) {
        // 只处理微信通知
        if (!WECHAT_PACKAGE.equals(sbn.getPackageName())) {
            return;
        }
        
        // 重新加载关键词（支持动态更新）
        loadKeywords();
        
        // 提取通知内容
        Bundle extras = sbn.getNotification().extras;
        if (extras == null) {
            return;
        }
        
        String title = extras.getString(Notification.EXTRA_TITLE);
        String text = extras.getString(Notification.EXTRA_TEXT);
        String bigText = extras.getString(Notification.EXTRA_BIG_TEXT);
        
        // 组合所有文本内容
        StringBuilder fullText = new StringBuilder();
        if (title != null) {
            fullText.append(title).append(" ");
        }
        if (text != null) {
            fullText.append(text).append(" ");
        }
        if (bigText != null) {
            fullText.append(bigText);
        }
        
        String content = fullText.toString();
        Log.d(TAG, "收到微信通知: " + content);
        
        // 关键词匹配
        for (String keyword : keywords) {
            if (content.contains(keyword)) {
                Log.d(TAG, "匹配到关键词: " + keyword);
                sendAlert(title, content, keyword);
                break; // 只提醒一次
            }
        }
    }
    
    @Override
    public void onNotificationRemoved(StatusBarNotification sbn) {
        // 可选：处理通知被移除的情况
    }
    
    private void loadKeywords() {
        keywords = prefs.getStringSet(MainActivity.KEY_KEYWORDS, new HashSet<>());
        
        // 如果还没有关键词，添加默认关键词
        if (keywords.isEmpty()) {
            keywords = new HashSet<>();
            keywords.add("红黄土");
            keywords.add("干杂土");
            keywords.add("代运");
            keywords.add("放飞");
            keywords.add("红砖渣");
            
            SharedPreferences.Editor editor = prefs.edit();
            editor.putStringSet(MainActivity.KEY_KEYWORDS, keywords);
            editor.apply();
        }
    }
    
    private void sendAlert(String title, String content, String matchedKeyword) {
        // 创建提醒通知
        NotificationCompat.Builder builder = new NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("⚠️ 关键词提醒")
            .setContentText("匹配到关键词: " + matchedKeyword)
            .setStyle(new NotificationCompat.BigTextStyle()
                .bigText("群名/联系人: " + title + "\n" +
                        "消息内容: " + content + "\n" +
                        "匹配关键词: " + matchedKeyword))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setVibrate(new long[]{0, 500, 200, 500});
        
        NotificationManager manager = getSystemService(NotificationManager.class);
        manager.notify((int) System.currentTimeMillis(), builder.build());
    }
    
    private void createNotificationChannel() {
        NotificationChannel channel = new NotificationChannel(
            CHANNEL_ID,
            "关键词提醒",
            NotificationManager.IMPORTANCE_HIGH
        );
        channel.setDescription("微信关键词匹配提醒");
        channel.enableVibration(true);
        
        NotificationManager manager = getSystemService(NotificationManager.class);
        manager.createNotificationChannel(channel);
    }
}
