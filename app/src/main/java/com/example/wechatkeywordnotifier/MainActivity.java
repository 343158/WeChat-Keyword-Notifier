package com.example.wechatkeywordnotifier;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Intent;
import android.os.Bundle;
import android.provider.Settings;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ListView;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Set;
import android.preference.PreferenceManager;
import android.content.SharedPreferences;

public class MainActivity extends AppCompatActivity {
    
    public static final String KEY_KEYWORDS = "keywords";
    private static final String CHANNEL_ID = "keyword_alert_channel";
    
    private SharedPreferences prefs;
    private ArrayList<String> keywordList;
    private ArrayAdapter<String> adapter;
    private ListView listView;
    private EditText editTextKeyword;
    private Button buttonAdd;
    private Button buttonRemove;
    
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        
        prefs = PreferenceManager.getDefaultSharedPreferences(this);
        keywordList = new ArrayList<>(loadKeywords());
        
        initViews();
        createNotificationChannel();
        checkNotificationPermission();
    }
    
    private void initViews() {
        editTextKeyword = findViewById(R.id.editTextKeyword);
        buttonAdd = findViewById(R.id.buttonAdd);
        buttonRemove = findViewById(R.id.buttonRemove);
        listView = findViewById(R.id.listViewKeywords);
        
        adapter = new ArrayAdapter<>(this, android.R.layout.simple_list_item_1, keywordList);
        listView.setAdapter(adapter);
        
        buttonAdd.setOnClickListener(v -> addKeyword());
        buttonRemove.setOnClickListener(v -> removeKeyword());
    }
    
    private void addKeyword() {
        String keyword = editTextKeyword.getText().toString().trim();
        if (!keyword.isEmpty() && !keywordList.contains(keyword)) {
            keywordList.add(keyword);
            saveKeywords();
            adapter.notifyDataSetChanged();
            editTextKeyword.setText("");
            Toast.makeText(this, "关键词已添加", Toast.LENGTH_SHORT).show();
        }
    }
    
    private void removeKeyword() {
        String keyword = editTextKeyword.getText().toString().trim();
        if (keywordList.remove(keyword)) {
            saveKeywords();
            adapter.notifyDataSetChanged();
            editTextKeyword.setText("");
            Toast.makeText(this, "关键词已删除", Toast.LENGTH_SHORT).show();
        }
    }
    
    private Set<String> loadKeywords() {
        return prefs.getStringSet(KEY_KEYWORDS, new HashSet<>());
    }
    
    private void saveKeywords() {
        SharedPreferences.Editor editor = prefs.edit();
        editor.putStringSet(KEY_KEYWORDS, new HashSet<>(keywordList));
        editor.apply();
    }
    
    private void createNotificationChannel() {
        NotificationChannel channel = new NotificationChannel(
            CHANNEL_ID,
            "关键词提醒",
            NotificationManager.IMPORTANCE_HIGH
        );
        channel.setDescription("微信关键词匹配提醒");
        
        NotificationManager manager = getSystemService(NotificationManager.class);
        manager.createNotificationChannel(channel);
    }
    
    private void checkNotificationPermission() {
        if (!isNotificationListenerEnabled()) {
            Toast.makeText(this, "请授权通知监听权限", Toast.LENGTH_LONG).show();
            startActivity(new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS));
        }
    }
    
    private boolean isNotificationListenerEnabled() {
        String pkgName = getPackageName();
        String flat = Settings.Secure.getString(getContentResolver(), "enabled_notification_listeners");
        return flat != null && flat.contains(pkgName);
    }
}