package com.example.wechatkeywordnotifier;

import android.content.Context;
import android.content.SharedPreferences;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 授权码管理器
 *
 * 每个授权码只能使用一次。
 * 验证成功后本地永久激活（无需复验）。
 *
 * 管理方式：
 * - 发码：在 license_codes.json 中添加新码
 * - 废码：验证后由管理员从 license_codes.json 中手动删除该码
 */
public class LicenseManager {

    private static final String PREFS_NAME = "license_prefs";
    private static final String KEY_VERIFIED = "is_verified";
    private static final String KEY_LICENSE_CODE = "license_code";

    // GitHub API
    private static final String REPO = "343158/WeChat-Keyword-Notifier";
    private static final String LICENSE_FILE = "license_codes.json";
    private static final String RAW_URL =
        "https://raw.githubusercontent.com/" + REPO + "/main/" + LICENSE_FILE;

    private final Context context;

    public LicenseManager(Context context) {
        this.context = context.getApplicationContext();
    }

    /**
     * 检查是否已激活（本地检查，永久有效）
     */
    public boolean isVerified() {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        return prefs.getBoolean(KEY_VERIFIED, false);
    }

    /**
     * 验证授权码
     * 成功后本地永久激活
     */
    public VerifyResult verifyOnline(String code) {
        if (code == null || code.trim().isEmpty()) {
            return new VerifyResult(false, "请输入授权码");
        }
        code = code.trim();

        try {
            // 1. 读取当前授权码列表
            String json = fetchUrl(RAW_URL);
            if (json == null || json.isEmpty()) {
                return new VerifyResult(false, "网络连接失败，请检查网络后重试");
            }

            JSONObject root = new JSONObject(json);
            JSONArray codesArray = root.getJSONArray("codes");

            // 2. 查找匹配的授权码
            boolean found = false;
            for (int i = 0; i < codesArray.length(); i++) {
                if (code.equalsIgnoreCase(codesArray.getString(i))) {
                    found = true;
                    break;
                }
            }

            if (!found) {
                return new VerifyResult(false, "授权码无效或已被使用");
            }

            // 3. 本地永久激活
            SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
            prefs.edit()
                .putBoolean(KEY_VERIFIED, true)
                .putString(KEY_LICENSE_CODE, code)
                .apply();

            return new VerifyResult(true, "激活成功！");

        } catch (Exception e) {
            e.printStackTrace();
            return new VerifyResult(false, "验证失败，请检查网络");
        }
    }

    /**
     * 清除授权
     */
    public void clearLicense() {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        prefs.edit().clear().apply();
    }

    /**
     * 获取当前授权码
     */
    public String getCurrentCode() {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getString(KEY_LICENSE_CODE, "");
    }

    private String fetchUrl(String urlString) {
        AtomicReference<String> result = new AtomicReference<>(null);
        CountDownLatch latch = new CountDownLatch(1);

        new Thread(() -> {
            try {
                URL url = new URL(urlString);
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("GET");
                conn.setConnectTimeout(10000);
                conn.setReadTimeout(10000);
                conn.setRequestProperty("User-Agent", "WeChatNotifier/2.0");

                if (conn.getResponseCode() == 200) {
                    BufferedReader reader = new BufferedReader(
                        new InputStreamReader(conn.getInputStream(), "UTF-8"));
                    StringBuilder sb = new StringBuilder();
                    String line;
                    while ((line = reader.readLine()) != null) {
                        sb.append(line);
                    }
                    reader.close();
                    result.set(sb.toString());
                }
                conn.disconnect();
            } catch (Exception e) {
                e.printStackTrace();
            } finally {
                latch.countDown();
            }
        }).start();

        try {
            latch.await(15, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return result.get();
    }

    public static class VerifyResult {
        public final boolean success;
        public final String message;
        public VerifyResult(boolean success, String message) {
            this.success = success;
            this.message = message;
        }
    }
}
