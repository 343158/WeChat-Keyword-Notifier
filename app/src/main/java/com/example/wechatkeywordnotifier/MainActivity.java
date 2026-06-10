package com.example.wechatkeywordnotifier;

import android.accessibilityservice.AccessibilityServiceInfo;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.text.TextUtils;
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

        // 加载关键词
        loadKeywords();

        // 加载消息历史
        loadMessageHistory();

        // 设置消息列表适配器
        messageAdapter = new MessageAdapter(this, messageList);
        lvMessages.setAdapter(messageAdapter);

        // 添加关键词
        btnAdd.setOnClickListener(v -> addKeyword());

        // 清空历史
        btnClearHistory.setOnClickListener(v -> clearHistory());

        // 点击消息显示详情
        lvMessages.setOnItemClickListener((parent, view, position, id) -> {
            MessageItem item = messageList.get(position);
            showMessageDetail(item);
        });

        // 长按消息删除
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
            tvKeywords.setText("干杂土, 砖渣");
            // 设置默认关键词
            saveKeywords(new HashSet<>(Arrays.asList("干杂土", "砖渣")));
        } else {
            tvKeywords.setText(TextUtils.join(", ", keywords));
        }
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
                    Toast.makeText(this, "已清空历史记录", Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void showMessageDetail(MessageItem item) {
        StringBuilder sb = new StringBuilder();
        sb.append("时间: ").append(item.time).append("\n\n");
        sb.append("关键词: ").append(item.keyword).append("\n\n");
        if (!TextUtils.isEmpty(item.sender)) {
            sb.append("发送者: ").append(item.sender).append("\n\n");
        }
        if (!TextUtils.isEmpty(item.group)) {
            sb.append("群名: ").append(item.group).append("\n\n");
        }
        sb.append("内容:\n").append(item.content);

        new AlertDialog.Builder(this)
                .setTitle("消息详情")
                .setMessage(sb.toString())
                .setPositiveButton("确定", null)
                .show();
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
            Toast.makeText(this, "请授权通知监听权限", Toast.LENGTH_LONG).show();
        } else {
            tvStatus.setText("监听中...");
            tvStatus.setTextColor(getResources().getColor(android.R.color.holo_green_dark));
        }
    }

    // 消息数据类
    static class MessageItem {
        String time;
        String keyword;
        String sender;
        String content;
        String group;
    }

    // 消息列表适配器
    static class MessageAdapter extends ArrayAdapter<MessageItem> {
        private final Context context;
        private final List<MessageItem> items;

        public MessageAdapter(Context context, List<MessageItem> items) {
            super(context, R.layout.message_list_item, items);
            this.context = context;
            this.items = items;
        }

        @Override
        public View getView(int position, View convertView, android.view.ViewGroup parent) {
            if (convertView == null) {
                convertView = LayoutInflater.from(context)
                        .inflate(R.layout.message_list_item, null);
            }

            MessageItem item = items.get(position);
            
            TextView tvTime = convertView.findViewById(R.id.tvTime);
            TextView tvKeyword = convertView.findViewById(R.id.tvKeyword);
            TextView tvSender = convertView.findViewById(R.id.tvSender);
            TextView tvContent = convertView.findViewById(R.id.tvContent);

            tvTime.setText(item.time);
            tvKeyword.setText("🔔 " + item.keyword);
            
            if (!TextUtils.isEmpty(item.group)) {
                tvSender.setText(item.group + " - " + item.sender);
            } else {
                tvSender.setText(item.sender);
            }
            
            // 显示内容前30个字符
            String displayContent = item.content;
            if (displayContent.length() > 30) {
                displayContent = displayContent.substring(0, 30) + "...";
            }
            tvContent.setText(displayContent);

            return convertView;
        }
    }
}
