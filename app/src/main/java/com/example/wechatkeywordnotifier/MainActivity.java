package com.example.wechatkeywordnotifier;

import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.TextUtils;
import android.text.style.ForegroundColorSpan;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.*;
import android.widget.CompoundButton;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class MainActivity extends AppCompatActivity {
    private static final String PREFS_NAME = "WeChatNotifier";
    private static final String KEY_HISTORY = "message_history";
    private static final int MAX_HISTORY = 200;

    // 共享的 PendingIntent 缓存 (静态，与 WeChatNotificationListener 共享)
    public static final ConcurrentHashMap<String, PendingIntent> pendingIntentCache = new ConcurrentHashMap<>();

    private ListView lvMessages;
    private TextView tvStatus;
    private List<MessageItem> messageList;
    private SectionedMessageAdapter adapter;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        lvMessages = findViewById(R.id.lvMessages);
        tvStatus = findViewById(R.id.tvStatus);
        Button btnClearHistory = findViewById(R.id.btnClearHistory);
        Button btnKeywords = findViewById(R.id.btnKeywords);
        Button btnViewMessages = findViewById(R.id.btnViewMessages);
        Button btnRingtone = findViewById(R.id.btnRingtone);
        Button btnSettings = findViewById(R.id.btnSettings);
        Switch switchTTS = findViewById(R.id.switchTTS);

        // 语音播报开关
        SharedPreferences prefsMain = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        switchTTS.setChecked(prefsMain.getBoolean("tts_enabled", true));
        switchTTS.setOnCheckedChangeListener((buttonView, isChecked) -> {
            prefsMain.edit().putBoolean("tts_enabled", isChecked).apply();
            Toast.makeText(this, isChecked ? "语音播报已开启" : "语音播报已关闭",
                    Toast.LENGTH_SHORT).show();
        });

        // 4个快捷入口
        btnKeywords.setOnClickListener(v ->
                startActivity(new Intent(this, KeywordsActivity.class)));

        btnViewMessages.setOnClickListener(v -> {
            // 滚动到消息列表
            lvMessages.smoothScrollToPosition(0);
        });

        btnRingtone.setOnClickListener(v -> {
            // 打开通知渠道设置（铃声）
            if (Build.VERSION.SDK_INT >= 26) {
                Intent intent = new Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS);
                intent.putExtra(Settings.EXTRA_APP_PACKAGE, getPackageName());
                intent.putExtra(Settings.EXTRA_CHANNEL_ID, "wechat_keyword_alert");
                startActivity(intent);
            } else {
                Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
                intent.setData(Uri.parse("package:" + getPackageName()));
                startActivity(intent);
            }
        });

        btnSettings.setOnClickListener(v ->
                startActivity(new Intent(this, SettingsActivity.class)));

        // 消息列表初始化
        messageList = new ArrayList<>();
        adapter = new SectionedMessageAdapter(this);
        lvMessages.setAdapter(adapter);

        // 点击消息 -> 弹出详情对话框
        lvMessages.setOnItemClickListener((parent, view, position, id) -> {
            MessageItem item = adapter.getRawItem(position);
            if (item != null) {
                showMessageDetail(item);
            }
        });

        // 长按删除
        lvMessages.setOnItemLongClickListener((parent, view, position, id) -> {
            MessageItem item = adapter.getRawItem(position);
            if (item != null) {
                showDeleteDialog(item);
                return true;
            }
            return false;
        });

        btnClearHistory.setOnClickListener(v -> {
            new AlertDialog.Builder(this)
                    .setTitle("确认清空")
                    .setMessage("确定要清空所有消息记录吗？")
                    .setPositiveButton("确定", (dialog, which) -> {
                        getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                                .edit()
                                .putString(KEY_HISTORY, "[]")
                                .apply();
                        messageList.clear();
                        pendingIntentCache.clear();
                        adapter.notifyDataSetChanged();
                    })
                    .setNegativeButton("取消", null)
                    .show();
        });

        // 状态栏点击跳转权限设置
        tvStatus.setOnClickListener(v -> {
            Intent intent = new Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS");
            startActivity(intent);
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        loadMessageHistory();
        adapter.notifyDataSetChanged();
        checkAllPermissions();
    }

    private void checkAllPermissions() {
        StringBuilder sb = new StringBuilder();

        // 1. 通知监听权限
        String serviceName = getPackageName() + "/" + WeChatNotificationListener.class.getName();
        String enabledListeners = Settings.Secure.getString(getContentResolver(),
                "enabled_notification_listeners");
        boolean hasListener = enabledListeners != null && enabledListeners.contains(serviceName);
        if (!hasListener) {
            sb.append("❌ 需授权通知监听 | ");
        }

        // 2. 通知发送权限 (Android 13+)
        if (Build.VERSION.SDK_INT >= 33) {
            if (checkSelfPermission("android.permission.POST_NOTIFICATIONS")
                    != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                sb.append("❌ 需通知权限 | ");
            }
        }

        // 3. 悬浮窗权限
        if (Build.VERSION.SDK_INT >= 23) {
            if (!Settings.canDrawOverlays(this)) {
                sb.append("❌ 需悬浮窗权限 | ");
            }
        }

        String status = sb.toString();
        if (TextUtils.isEmpty(status)) {
            tvStatus.setText("✅ 所有权限已授权，监听中...");
            tvStatus.setTextColor(0xFF4CAF50);
        } else {
            // 去掉末尾的 " | "
            if (status.endsWith(" | ")) {
                status = status.substring(0, status.length() - 3);
            }
            tvStatus.setText(status);
            tvStatus.setTextColor(0xFFF44336);
        }
    }

    private void loadMessageHistory() {
        messageList.clear();
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
                item.messageKey = obj.optString("messageKey", "");
                messageList.add(item);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    /**
     * 弹出消息详情对话框
     */
    private void showMessageDetail(MessageItem item) {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("消息详情");

        StringBuilder content = new StringBuilder();
        if (!TextUtils.isEmpty(item.group)) {
            content.append("群组: ").append(item.group).append("\n");
        }
        content.append("发送者: ").append(item.sender).append("\n");
        content.append("时间: ").append(item.time).append("\n");
        content.append("关键词: ").append(item.keyword).append("\n");
        content.append("内容:\n").append(item.content);

        builder.setMessage(content.toString());

        builder.setPositiveButton("📱 打开微信", (dialog, which) -> {
            openWeChatChat(item);
        });

        // 检测是否有电话号码
        final List<String> phones = extractPhoneNumbers(item);
        if (!phones.isEmpty()) {
            builder.setNeutralButton("📞 拨打电话", (dialog, which) -> {
                if (phones.size() == 1) {
                    dialPhoneNumber(phones.get(0));
                } else {
                    showPhoneSelectionDialog(phones);
                }
            });
        }

        builder.setNegativeButton("关闭", null);
        builder.show();
    }

    /**
     * 打开微信对应聊天
     */
    private void openWeChatChat(MessageItem item) {
        // 优先使用缓存的 PendingIntent
        if (!TextUtils.isEmpty(item.messageKey)) {
            PendingIntent pi = pendingIntentCache.get(item.messageKey);
            if (pi != null) {
                try {
                    pi.send();
                    return;
                } catch (Exception e) {
                    // 失败则降级
                }
            }
        }

        // 降级：直接打开微信
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

    /**
     * 从消息中提取电话号码
     */
    private List<String> extractPhoneNumbers(MessageItem item) {
        List<String> phones = new ArrayList<>();

        // 从消息内容中提取
        if (!TextUtils.isEmpty(item.content)) {
            java.util.regex.Pattern pattern =
                    java.util.regex.Pattern.compile("1[3-9]\\d{9}");
            java.util.regex.Matcher matcher = pattern.matcher(item.content);
            while (matcher.find()) {
                phones.add(matcher.group());
            }
        }

        // 从发送者名字中提取
        if (!TextUtils.isEmpty(item.sender)) {
            java.util.regex.Pattern pattern =
                    java.util.regex.Pattern.compile("1[3-9]\\d{9}");
            java.util.regex.Matcher matcher = pattern.matcher(item.sender);
            while (matcher.find()) {
                if (!phones.contains(matcher.group())) {
                    phones.add(matcher.group());
                }
            }
        }

        return phones;
    }

    private void dialPhoneNumber(String phone) {
        try {
            Intent intent = new Intent(Intent.ACTION_DIAL);
            intent.setData(Uri.parse("tel:" + phone));
            startActivity(intent);
        } catch (Exception e) {
            Toast.makeText(this, "拨号失败", Toast.LENGTH_SHORT).show();
        }
    }

    private void showPhoneSelectionDialog(List<String> phones) {
        String[] items = new String[phones.size()];
        for (int i = 0; i < phones.size(); i++) {
            items[i] = phones.get(i);
        }
        new AlertDialog.Builder(this)
                .setTitle("选择电话号码")
                .setItems(items, (dialog, which) -> dialPhoneNumber(phones.get(which)))
                .setNegativeButton("取消", null)
                .show();
    }

    private void showDeleteDialog(MessageItem item) {
        new AlertDialog.Builder(this)
                .setTitle("删除确认")
                .setMessage("确定要删除这条记录吗？")
                .setPositiveButton("确定", (dialog, which) -> {
                    messageList.remove(item);
                    saveMessageHistory();
                    adapter.notifyDataSetChanged();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void saveMessageHistory() {
        try {
            JSONArray jsonArray = new JSONArray();
            for (int i = messageList.size() - 1; i >= 0; i--) {
                MessageItem item = messageList.get(i);
                JSONObject obj = new JSONObject();
                obj.put("time", item.time);
                obj.put("keyword", item.keyword);
                obj.put("sender", item.sender);
                obj.put("content", item.content);
                obj.put("group", item.group);
                obj.put("messageKey", item.messageKey);
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

    // ============ Model classes ============

    static class MessageItem {
        String time;
        String keyword;
        String sender;
        String content;
        String group;
        String messageKey; // 用于查找 PendingIntent 的唯一键
    }

    // ============ Sectioned Adapter ============

    private static class DisplayItem {
        boolean isHeader;
        String groupName;
        int groupCount;
        MessageItem message;
    }

    private class SectionedMessageAdapter extends BaseAdapter {
        private static final int VIEW_TYPE_HEADER = 0;
        private static final int VIEW_TYPE_MESSAGE = 1;

        private final Context context;
        private final List<DisplayItem> displayItems;

        public SectionedMessageAdapter(Context context) {
            this.context = context;
            this.displayItems = new ArrayList<>();
            buildDisplayList();
        }

        public void buildDisplayList() {
            displayItems.clear();

            // 按 group name 分组
            Map<String, List<MessageItem>> grouped = new LinkedHashMap<>();
            String noGroup = "(未分组)";
            for (MessageItem item : messageList) {
                String key = TextUtils.isEmpty(item.group) ? noGroup : item.group;
                if (!grouped.containsKey(key)) {
                    grouped.put(key, new ArrayList<>());
                }
                grouped.get(key).add(item);
            }

            for (Map.Entry<String, List<MessageItem>> entry : grouped.entrySet()) {
                // 添加分组标题
                DisplayItem header = new DisplayItem();
                header.isHeader = true;
                header.groupName = entry.getKey();
                header.groupCount = entry.getValue().size();
                displayItems.add(header);

                // 添加该分组下的消息
                for (MessageItem msg : entry.getValue()) {
                    DisplayItem di = new DisplayItem();
                    di.isHeader = false;
                    di.message = msg;
                    displayItems.add(di);
                }
            }
        }

        public MessageItem getRawItem(int position) {
            if (position >= 0 && position < displayItems.size()) {
                DisplayItem di = displayItems.get(position);
                return di.isHeader ? null : di.message;
            }
            return null;
        }

        @Override
        public int getCount() {
            return displayItems.size();
        }

        @Override
        public Object getItem(int position) {
            return displayItems.get(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public int getViewTypeCount() {
            return 2;
        }

        @Override
        public int getItemViewType(int position) {
            return displayItems.get(position).isHeader ? VIEW_TYPE_HEADER : VIEW_TYPE_MESSAGE;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            DisplayItem item = displayItems.get(position);

            if (item.isHeader) {
                return getHeaderView(item, convertView, parent);
            } else {
                return getMessageView(item.message, convertView, parent);
            }
        }

        private View getHeaderView(DisplayItem item, View convertView, ViewGroup parent) {
            if (convertView == null) {
                convertView = LayoutInflater.from(context)
                        .inflate(R.layout.message_group_header, parent, false);
            }

            TextView tvGroupName = convertView.findViewById(R.id.tvGroupName);
            TextView tvGroupCount = convertView.findViewById(R.id.tvGroupCount);

            tvGroupName.setText("▼  " + item.groupName);
            tvGroupCount.setText("(" + item.groupCount + "条)");

            return convertView;
        }

        private View getMessageView(MessageItem msg, View convertView, ViewGroup parent) {
            if (convertView == null) {
                convertView = LayoutInflater.from(context)
                        .inflate(R.layout.message_list_item, parent, false);
            }

            TextView tvTime = convertView.findViewById(R.id.tvTime);
            TextView tvKeyword = convertView.findViewById(R.id.tvKeyword);
            TextView tvSender = convertView.findViewById(R.id.tvSender);
            TextView tvContent = convertView.findViewById(R.id.tvContent);
            Button btnOpenWeChat = convertView.findViewById(R.id.btnOpenWeChat);
            Button btnCall = convertView.findViewById(R.id.btnCall);

            // 时间（简化显示）
            String displayTime = msg.time;
            if (displayTime.length() > 16) {
                displayTime = displayTime.substring(5, 16); // "06-10 10:30"
            }
            tvTime.setText(displayTime);

            // 关键词标签
            tvKeyword.setText("#" + msg.keyword);
            tvKeyword.setTextColor(0xFFFF5722);

            // 发送者
            if (!TextUtils.isEmpty(msg.group)) {
                tvSender.setText(msg.group + " - " + msg.sender);
            } else {
                tvSender.setText(msg.sender);
            }

            // 消息内容（带关键词红色高亮）
            String displayContent = msg.content;
            if (displayContent.length() > 50) {
                displayContent = displayContent.substring(0, 50) + "...";
            }
            SpannableString spannable = new SpannableString(displayContent);
            // 高亮关键词（大小写敏感）
            if (!TextUtils.isEmpty(msg.keyword)) {
                int idx = displayContent.indexOf(msg.keyword);
                if (idx >= 0) {
                    spannable.setSpan(new ForegroundColorSpan(0xFFFF5722),
                            idx, idx + msg.keyword.length(),
                            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                }
            }
            tvContent.setText(spannable);

            // 微信图标 - 跳转到聊天
            btnOpenWeChat.setOnClickListener(v -> openWeChatChat(msg));

            // 电话图标 - 拨打电话
            btnCall.setOnClickListener(v -> {
                List<String> phones = extractPhoneNumbers(msg);
                if (!phones.isEmpty()) {
                    if (phones.size() == 1) {
                        dialPhoneNumber(phones.get(0));
                    } else {
                        showPhoneSelectionDialog(phones);
                    }
                } else {
                    Toast.makeText(context, "未找到电话号码", Toast.LENGTH_SHORT).show();
                }
            });

            return convertView;
        }

        @Override
        public void notifyDataSetChanged() {
            buildDisplayList();
            super.notifyDataSetChanged();
        }
    }
}
