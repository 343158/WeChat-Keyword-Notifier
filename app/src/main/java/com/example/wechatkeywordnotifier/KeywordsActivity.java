package com.example.wechatkeywordnotifier;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.*;
import androidx.appcompat.app.AppCompatActivity;
import java.util.*;

public class KeywordsActivity extends AppCompatActivity {

    public static final String PREFS_NAME = "WeChatNotifier";
    public static final String KEY_KEYWORDS = "keywords";
    private static final String[] DEFAULT_KEYWORDS = {"红黄土", "干杂土", "代运", "放飞", "红砖渣"};

    private EditText etKeyword;
    private ListView lvKeywords;
    private List<String> keywordsList;
    private KeywordsAdapter adapter;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_keywords);

        etKeyword = findViewById(R.id.etKeyword);
        lvKeywords = findViewById(R.id.lvKeywords);
        Button btnAdd = findViewById(R.id.btnAdd);
        Button btnReset = findViewById(R.id.btnResetDefaults);

        keywordsList = new ArrayList<>();
        refreshKeywordList();

        adapter = new KeywordsAdapter(this, keywordsList);
        lvKeywords.setAdapter(adapter);

        btnAdd.setOnClickListener(v -> {
            String keyword = etKeyword.getText().toString().trim();
            if (TextUtils.isEmpty(keyword)) {
                Toast.makeText(this, "请输入关键词", Toast.LENGTH_SHORT).show();
                return;
            }
            Set<String> keywords = getKeywords();
            if (keywords.contains(keyword)) {
                Toast.makeText(this, "关键词已存在", Toast.LENGTH_SHORT).show();
                return;
            }
            keywords.add(keyword);
            saveKeywords(keywords);
            refreshKeywordList();
            etKeyword.setText("");
            Toast.makeText(this, "已添加: " + keyword, Toast.LENGTH_SHORT).show();
        });

        btnReset.setOnClickListener(v -> {
            Set<String> defaults = new HashSet<>(Arrays.asList(DEFAULT_KEYWORDS));
            saveKeywords(defaults);
            refreshKeywordList();
            Toast.makeText(this, "已恢复默认关键词", Toast.LENGTH_SHORT).show();
        });
    }

    private void refreshKeywordList() {
        keywordsList.clear();
        keywordsList.addAll(getKeywords());
        if (adapter != null) {
            adapter.notifyDataSetChanged();
        }
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

    private static class KeywordsAdapter extends ArrayAdapter<String> {
        private final List<String> items;
        private final SharedPreferences prefs;

        public KeywordsAdapter(Context context, List<String> items) {
            super(context, android.R.layout.simple_list_item_1, items);
            this.items = items;
            this.prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            if (convertView == null) {
                convertView = LayoutInflater.from(getContext())
                        .inflate(android.R.layout.simple_list_item_1, null);
            }

            TextView tv = (TextView) convertView;
            tv.setText("✕  " + items.get(position));
            tv.setTextSize(16);
            tv.setPadding(16, 12, 16, 12);
            tv.setTextColor(0xFF333333);

            // 点击删除
            tv.setOnClickListener(v -> {
                String keyword = items.get(position);
                Set<String> keywords = prefs.getStringSet(KEY_KEYWORDS, new HashSet<>());
                keywords.remove(keyword);
                prefs.edit().putStringSet(KEY_KEYWORDS, keywords).apply();
                items.remove(position);
                notifyDataSetChanged();
                Toast.makeText(getContext(), "已删除: " + keyword, Toast.LENGTH_SHORT).show();
            });

            return tv;
        }
    }
}
