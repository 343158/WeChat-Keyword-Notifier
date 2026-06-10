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
 * 授权码列表存放在 GitHub 仓库的原始文件中：
 * https://raw.githubusercontent.com/343158/WeChat-Keyword-Notifier/main/license_codes.json
 * 
 * 格式: {"codes": ["CODE1", "CODE2", ...]}
 * 
 * 管理员只需更新这个文件即可管理授权码。
 */
public class LicenseManager {

    private static final String PREFS_NAME = "license_prefs";
    private static final String KEY_VERIFIED = "is_verified";
    private static final String KEY_LICENSE_CODE = "license_code";
    private static final String KEY_VERIFY_TIME = "verify_time";
    
    // 授权码在线列表地址（GitHub raw）
    private static final String LICENSE_URL = 
        "https://raw.githubusercontent.com/343158/WeChat-Keyword-Notifier/main/license_codes.json";
    
    // 离线缓存有效期：7天（毫秒）
    private static final long OFFLINE_GRACE_MS = 7L * 24 * 60 * 60 * 1000;
    
    private final Context context;

    public LicenseManager(Context context) {
        this.context = context.getApplicationContext();
    }

    /**
     * 检查是否已授权（离线检查，用于快速启动）
     * 联网时在 AuthActivity 中做在线复验
     */
    public boolean isVerified() {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        if (!prefs.getBoolean(KEY_VERIFIED, false)) {
            return false;
        }
        String code = prefs.getString(KEY_LICENSE_CODE, "");
        if (code.isEmpty()) {
            return false;
        }
        // 检查离线缓存是否过期
        long lastVerify = prefs.getLong(KEY_VERIFY_TIME, 0);
        if (System.currentTimeMillis() - lastVerify > OFFLINE_GRACE_MS) {
            return false; // 超过7天需要重新联网验证
        }
        return true;
    }

    /**
     * 在线验证授权码
     * 
     * @param code 用户输入的授权码
     * @return VerifyResult 包含验证结果和提示信息
     */
    public VerifyResult verifyOnline(String code) {
        if (code == null || code.trim().isEmpty()) {
            return new VerifyResult(false, "请输入授权码");
        }
        code = code.trim();

        try {
            // 从 GitHub 获取授权码列表
            String json = fetchUrl(LICENSE_URL);
            if (json == null || json.isEmpty()) {
                // 网络失败时，检查本地是否有有效缓存
                SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
                String cachedCode = prefs.getString(KEY_LICENSE_CODE, "");
                long lastVerify = prefs.getLong(KEY_VERIFY_TIME, 0);
                if (code.equals(cachedCode) && 
                    System.currentTimeMillis() - lastVerify <= OFFLINE_GRACE_MS) {
                    // 延长缓存（每7天需要联网一次）
                    prefs.edit().putLong(KEY_VERIFY_TIME, System.currentTimeMillis()).apply();
                    return new VerifyResult(true, "离线验证通过");
                }
                return new VerifyResult(false, "网络连接失败，请检查网络后重试");
            }

            JSONObject root = new JSONObject(json);
            JSONArray codesArray = root.getJSONArray("codes");

            // 检查授权码是否在列表中
            boolean found = false;
            for (int i = 0; i < codesArray.length(); i++) {
                if (code.equalsIgnoreCase(codesArray.getString(i))) {
                    found = true;
                    break;
                }
            }

            if (found) {
                // 验证通过，保存到本地
                SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
                prefs.edit()
                    .putBoolean(KEY_VERIFIED, true)
                    .putString(KEY_LICENSE_CODE, code)
                    .putLong(KEY_VERIFY_TIME, System.currentTimeMillis())
                    .apply();
                return new VerifyResult(true, "验证通过");
            } else {
                return new VerifyResult(false, "授权码无效，请联系管理员获取");
            }

        } catch (Exception e) {
            e.printStackTrace();
            return new VerifyResult(false, "验证失败: " + e.getMessage());
        }
    }

    /**
     * 清除授权（用于退出授权/作废）
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
        AtomicReference<Exception> error = new AtomicReference<>();
        CountDownLatch latch = new CountDownLatch(1);

        new Thread(() -> {
            try {
                URL url = new URL(urlString);
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("GET");
                conn.setConnectTimeout(10000);
                conn.setReadTimeout(10000);
                conn.setRequestProperty("User-Agent", "WeChatNotifier/2.0");

                int responseCode = conn.getResponseCode();
                if (responseCode == 200) {
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
                error.set(e);
            } finally {
                latch.countDown();
            }
        }).start();

        try {
            latch.await(15, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        if (error.get() != null) {
            return null;
        }
        return result.get();
    }

    /**
     * 验证结果
     */
    public static class VerifyResult {
        public final boolean success;
        public final String message;

        public VerifyResult(boolean success, String message) {
            this.success = success;
            this.message = message;
        }
    }
}
