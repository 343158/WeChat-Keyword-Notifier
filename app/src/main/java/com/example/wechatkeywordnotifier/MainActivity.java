package com.example.wechatkeywordnotifier;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.*;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.*;

public class MainActivity extends AppCompatActivity {
    private static final String PREFS_NAME = "WeChatNotifier";
    public static final String KEY_KEYWORDS = "keywords";
    private static final String KEY_HISTORY = "message_history";
    private static final int MAX_HISTORY = 100;

    private EditText etKeyword;
    private TextView tvKeywords;
    private ListView lvMessages;
    private TextView tvStatus;
    private List<MessageItem> messageList;
    private MessageAdapter messageAdapter;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        etKeyword = findViewById(R.id.etKeyword);
        tvKeywords = findViewById(R.id.tvKeywords);
        lvMessages = findViewById(R.id.lvMessages);
        tvStatus = findViewById(R.id.tvStatus);
        Button btnAdd = findViewById(R.id.btnAdd);
        Button btnClearHistory = findViewById(R.id.btnClearHistory);

        loadKeywords();
        loadMessageHistory();

        messageAdapter = new MessageAdapter(this, messageList);
        lvMessages.setAdapter(messageAdapter);

        btnAdd.setOnClickListener(v -> addKeyword());

        btnClearHistory.setOnClickListener(v -> clearHistory());

        // 点击消息 -> 跳转到微信对应聊天
        lvMessages.setOnItemClickListener((parent, view, position, id) -> {
            MessageItem item = messageList.get(position);
            openWeChatChat(item);
        });

        // 长按删除
        lvMessages.setOnItemLongClickListener((parent, view, position, id) -> {
            showDeleteDialog(position);
            return true;
        });

        checkPermission();
    }

    @Override
    protected void onResume() {
        super.onResume();
        loadMessageHistory();
        if (messageAdapter != null) {
            messageAdapter.notifyDataSetChanged();
        }
    }

    private void loadKeywords() {
        Set<String> keywords = getKeywords();
        if (keywords.isEmpty()) {
            saveKeywords(new HashSet<>(Arrays.asList("红黄土", "干杂土", "代运", "放飞", "红砖渣")));
        }
        keywords = getKeywords();
        tvKeywords.setText(TextUtils.join(", ", keywords));
    }

    private void addKeyword() {
        String keyword = etKeyword.getText().toString().trim();
        if (TextUtils.isEmpty(keyword)) {
            Toast.makeText(this, "请输入关键词", Toast.LENGTH_SHORT).show();
            return;
        }
        Set<String> keywords = getKeywords();
        keywords.add(keyword);
        saveKeywords(keywords);
        tvKeywords.setText(TextUtils.join(", ", keywords));
        etKeyword.setText("");
        Toast.makeText(this, "已添加: " + keyword, Toast.LENGTH_SHORT).show();
    }

    private Set<String> getKeywords() {
        return getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                .getStringSet(KEY_KEYWORDS, new HashSet<>());
    }

    private void saveKeywords(Set<String> keywords) {
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                .edit()
                .putStringSet(KEY_KEYWORDS, keywords)
                .apply();
    }

    private void loadMessageHistory() {
        messageList = new ArrayList<>();
        try {
            String historyJson = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                    .getString(KEY_HISTORY, "[]");
            JSONArray jsonArray = new JSONArray(historyJson);
            for (int i = jsonArray.length() - 1; i >= 0; i--) {
                JSONObject obj = jsonArray.getJSONObject(i);
                MessageItem item = new MessageItem();
                item.time = obj.optString("time", "");
                item.keyword = obj.optString("keyword", "");
                item.sender = obj.optString("sender", "");
                item.content = obj.optString("content", "");
                item.group = obj.optString("group", "");
                messageList.add(item);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void clearHistory() {
        new AlertDialog.Builder(this)
                .setTitle("确认清空")
                .setMessage("确定要清空所有消息记录吗？")
                .setPositiveButton("确定", (dialog, which) -> {
                    getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                            .edit()
                            .putString(KEY_HISTORY, "[]")
                            .apply();
                    loadMessageHistory();
                    messageAdapter.notifyDataSetChanged();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    /**
     * 点击消息 -> 打开微信对应的聊天
     * 利用微信通知中提取的群名/发送者信息
     */
    private void openWeChatChat(MessageItem item) {
        try {
            // 方案：通过微信的 Uri scheme 打开
            // 微信可以通过 com.tencent.mm 的 launcher activity 打开，但无法直接指定聊天
            // 最可行的方式：模拟通知点击行为，但需要 NotificationListenerService 缓存 PendingIntent
            
            // 如果有缓存的 PendingIntent（由 NotificationListenerService 保存），直接触发
            String pendingIntentData = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                    .getString("pending_intent_" + item.time, "");
            
            if (!TextUtils.isEmpty(pendingIntentData)) {
                // 有缓存的 Intent 数据
                // 注意：PendingIntent 无法直接序列化，这里改用通知方式
            }
            
            // 实际可行方案：直接打开微信主界面
            // 用户可以手动找到对应聊天
            Intent launchIntent = getPackageManager().getLaunchIntentForPackage("com.tencent.mm");
            if (launchIntent != null) {
                startActivity(launchIntent);
            } else {
                Toast.makeText(this, "未安装微信", Toast.LENGTH_SHORT).show();
            }
            
        } catch (Exception e) {
            Toast.makeText(this, "打开微信失败", Toast.LENGTH_SHORT).show();
        }
    }

    private void showDeleteDialog(int position) {
        new AlertDialog.Builder(this)
                .setTitle("删除确认")
                .setMessage("确定要删除这条记录吗？")
                .setPositiveButton("确定", (dialog, which) -> {
                    messageList.remove(position);
                    saveMessageHistory();
                    messageAdapter.notifyDataSetChanged();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void saveMessageHistory() {
        try {
            JSONArray jsonArray = new JSONArray();
            for (MessageItem item : messageList) {
                JSONObject obj = new JSONObject();
                obj.put("time", item.time);
                obj.put("keyword", item.keyword);
                obj.put("sender", item.sender);
                obj.put("content", item.content);
                obj.put("group", item.group);
                jsonArray.put(obj);
            }
            getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                    .edit()
                    .putString(KEY_HISTORY, jsonArray.toString())
                    .apply();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void checkPermission() {
        String serviceName = getPackageName() + "/" + WeChatNotificationListener.class.getName();
        String enabledListeners = Settings.Secure.getString(getContentResolver(), 
                "enabled_notification_listeners");
        if (enabledListeners == null || !enabledListeners.contains(serviceName)) {
            tvStatus.setText("需授权通知监听权限");
            tvStatus.setTextColor(getResources().getColor(android.R.color.holo_red_dark));
        } else {
            tvStatus.setText("监听中...");
            tvStatus.setTextColor(getResources().getColor(android.R.color.holo_green_dark));
        }
    }

    static class MessageItem {
        String time;
        String keyword;
        String sender;
        String content;
        String group;
    }

    static class MessageAdapter extends ArrayAdapter<MessageItem> {
        private final List<MessageItem> items;

        public MessageAdapter(Context context, List<MessageItem> items) {
            super(context, R.layout.message_list_item, items);
            this.items = items;
        }

        @Override
        public View getView(int position, View convertView, android.view.ViewGroup parent) {
            if (convertView == null) {
                convertView = LayoutInflater.from(getContext())
                        .inflate(R.layout.message_list_item, null);
            }

            MessageItem item = items.get(position);
            TextView tvTime = convertView.findViewById(R.id.tvTime);
            TextView tvKeyword = convertView.findViewById(R.id.tvKeyword);
            TextView tvSender = convertView.findViewById(R.id.tvSender);
            TextView tvContent = convertView.findViewById(R.id.tvContent);

            tvTime.setText(item.time);
            tvKeyword.setText("匹配: " + item.keyword);
            if (!TextUtils.isEmpty(item.group)) {
                tvSender.setText(item.group + " - " + item.sender);
            } else {
                tvSender.setText(item.sender);
            }
            String displayContent = item.content;
            if (displayContent.length() > 30) {
                displayContent = displayContent.substring(0, 30) + "...";
            }
            tvContent.setText(displayContent);
            return convertView;
        }
    }
}
