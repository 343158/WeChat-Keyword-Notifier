package com.example.wechatkeywordnotifier;

import android.content.SharedPreferences;
import android.os.Bundle;
import android.view.MenuItem;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.ListView;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class HistoryActivity extends AppCompatActivity {
    
    private static final String PREFS_HISTORY = "message_history";
    
    private ListView listView;
    private Button btnClear;
    private ArrayAdapter<String> adapter;
    private List<String> historyList;
    private SharedPreferences historyPrefs;
    
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_history);
        
        getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        getSupportActionBar().setTitle("消息历史记录");
        
        historyPrefs = getSharedPreferences(PREFS_HISTORY, MODE_PRIVATE);
        
        listView = findViewById(R.id.listViewHistory);
        btnClear = findViewById(R.id.btnClearHistory);
        
        historyList = new ArrayList<>();
        adapter = new ArrayAdapter<>(this, android.R.layout.simple_list_item_1, historyList);
        listView.setAdapter(adapter);
        
        btnClear.setOnClickListener(v -> clearHistory());
        
        loadHistory();
    }
    
    @Override
    protected void onResume() {
        super.onResume();
        loadHistory();
    }
    
    private void loadHistory() {
        historyList.clear();
        try {
            String jsonStr = historyPrefs.getString("history", "[]");
            JSONArray history = new JSONArray(jsonStr);
            
            // Reverse order (newest first)
            for (int i = history.length() - 1; i >= 0; i--) {
                JSONObject entry = history.getJSONObject(i);
                String time = entry.optString("time", "");
                String keyword = entry.optString("keyword", "");
                String title = entry.optString("title", "");
                String content = entry.optString("content", "");
                
                String display = String.format("[%s] 【%s】\n来自: %s\n内容: %s", 
                    time, keyword, title, content);
                historyList.add(display);
            }
            
            if (historyList.isEmpty()) {
                historyList.add("暂无匹配记录");
            }
        } catch (Exception e) {
            historyList.add("加载历史记录失败: " + e.getMessage());
        }
        
        adapter.notifyDataSetChanged();
    }
    
    private void clearHistory() {
        historyPrefs.edit().putString("history", "[]").apply();
        historyList.clear();
        historyList.add("暂无匹配记录");
        adapter.notifyDataSetChanged();
        Toast.makeText(this, "历史记录已清空", Toast.LENGTH_SHORT).show();
    }
    
    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == android.R.id.home) {
            finish();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }
}