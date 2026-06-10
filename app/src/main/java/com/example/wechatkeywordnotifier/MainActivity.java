package com.example.wechatkeywordnotifier;

import android.Manifest;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.*;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.*;

public class MainActivity extends AppCompatActivity {
    private static final String PREFS_NAME = "WeChatNotifier";
    public static final String KEY_KEYWORDS = "keywords";
    private static final String KEY_HISTORY = "message_history";
    private static final int MAX_HISTORY = 100;
    private static final int REQUEST_POST_NOTIFICATIONS = 1001;
    private static final int REQUEST_OVERLAY_PERMISSION = 1002;

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

        lvMessages.setOnItemClickListener((parent, view, position, id) -> {
            MessageItem item = messageList.get(position);
            openWeChatChat(item);
        });

        lvMessages.setOnItemLongClickListener((parent, view, position, id) -> {
            showDeleteDialog(position);
            return true;
        });

        // 检查所有权限
        checkAllPermissions();
    }

    @Override
    protected void onResume() {
        super.onResume();
        loadMessageHistory();
        if (messageAdapter != null) {
            messageAdapter.notifyDataSetChanged();
        }
        updatePermissionStatus();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_OVERLAY_PERMISSION) {
            updatePermissionStatus();
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_POST_NOTIFICATIONS) {
            updatePermissionStatus();
        }
    }

    /**
     * 检查所有必需权限，逐个引导授权
     */
    private void checkAllPermissions() {
        // 1. 通知发送权限 (Android 13+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                    != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this,
                        new String[]{Manifest.permission.POST_NOTIFICATIONS},
                        REQUEST_POST_NOTIFICATIONS);
                return; // 等待授权结果后再检查下一个
            }
        }

        // 2. 悬浮窗权限
        if (!Settings.canDrawOverlays(this)) {
            showOverlayPermissionDialog();
            return;
        }

        // 3. 通知监听权限
        if (!hasNotificationListenerPermission()) {
            showNotificationListenerDialog();
            return;
        }

        // 全部授权完成
        tvStatus.setText("✅ 监听中...");
        tvStatus.setTextColor(getResources().getColor(android.R.color.holo_green_dark));
        tvStatus.setOnClickListener(null);
    }

    private void updatePermissionStatus() {
        StringBuilder status = new StringBuilder();
        boolean allGranted = true;

        // 检查通知发送权限
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                    != PackageManager.PERMISSION_GRANTED) {
                status.append("❌ 通知权限\n");
                allGranted = false;
            }
        }

        // 检查悬浮窗权限
        if (!Settings.canDrawOverlays(this)) {
            status.append("❌ 悬浮窗权限\n");
            allGranted = false;
        }

        // 检查通知监听权限
        if (!hasNotificationListenerPermission()) {
            status.append("❌ 通知监听权限\n");
            allGranted = false;
        }

        if (allGranted) {
            tvStatus.setText("✅ 监听中...");
            tvStatus.setTextColor(getResources().getColor(android.R.color.holo_green_dark));
            tvStatus.setOnClickListener(null);
        } else {
            tvStatus.setText("⚠ 点击授权缺失权限");
            tvStatus.setTextColor(getResources().getColor(android.R.color.holo_red_dark));
            tvStatus.setOnClickListener(v -> checkAllPermissions());
        }
    }

    private boolean hasNotificationListenerPermission() {
        String serviceName = getPackageName() + "/" + WeChatNotificationListener.class.getName();
        String enabledListeners = Settings.Secure.getString(getContentResolver(),
                "enabled_notification_listeners");
        return enabledListeners != null && enabledListeners.contains(serviceName);
    }

    private void showOverlayPermissionDialog() {
        new AlertDialog.Builder(this)
                .setTitle("需要悬浮窗权限")
                .setMessage("本App需要悬浮窗权限来在屏幕顶部弹窗提醒您。\n\n点击[去授权]后，找到本App并打开开关。")
                .setPositiveButton("去授权", (dialog, which) -> {
                    Intent intent = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                            Uri.parse("package:" + getPackageName()));
                    startActivityForResult(intent, REQUEST_OVERLAY_PERMISSION);
                })
                .setNegativeButton("稍后", (dialog, which) -> updatePermissionStatus())
                .setCancelable(false)
                .show();
    }

    private void showNotificationListenerDialog() {
        new AlertDialog.Builder(this)
                .setTitle("需要通知监听权限")
                .setMessage("本App需要[通知使用权]才能监听微信消息。\n\n点击[去授权]后，在设置页面找到本App，打开开关即可。")
                .setPositiveButton("去授权", (dialog, which) -> {
                    try {
                        Intent intent = new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS);
                        startActivity(intent);
                    } catch (Exception e) {
                        Intent intent = new Intent(Settings.ACTION_SETTINGS);
                        startActivity(intent);
                        Toast.makeText(this, "请在设置中找到[通知使用权]并授权本App", Toast.LENGTH_LONG).show();
                    }
                })
                .setNegativeButton("稍后", (dialog, which) -> updatePermissionStatus())
                .setCancelable(false)
                .show();
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

    private void openWeChatChat(MessageItem item) {
        try {
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
