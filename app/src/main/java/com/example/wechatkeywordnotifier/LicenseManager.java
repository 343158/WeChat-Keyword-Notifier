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
 * 一次性授权码机制：
 * 1. 用户输入授权码 → 联网校验 → 匹配则本地永久激活
 * 2. 每次启动时，联网检查已用码是否仍在白名单中
 *    - 码已不在列表 → 自动清除本地激活 → 重新要求输入授权码
 * 3. 管理员从 license_codes.json 手动删除已用码即可作废
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
     * 检查是否已激活（仅本地检查）
     */
    public boolean isVerified() {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        return prefs.getBoolean(KEY_VERIFIED, false);
    }

    /**
     * 联网验证当前授权码是否仍然有效
     * 如果码已从列表移除，自动清除本地激活状态
     *
     * @return true = 码仍然有效，false = 码已作废或网络失败无法验证
     */
    public boolean verifyCurrentCodeStillValid() {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        if (!prefs.getBoolean(KEY_VERIFIED, false)) {
            return false; // 未激活
        }

        String savedCode = prefs.getString(KEY_LICENSE_CODE, "");
        if (savedCode.isEmpty()) {
            return false;
        }

        try {
            String json = fetchUrl(RAW_URL);
            if (json == null || json.isEmpty()) {
                // 网络不通，不强制失效（容错）
                return true;
            }

            JSONObject root = new JSONObject(json);
            JSONArray codesArray = root.getJSONArray("codes");

            boolean stillValid = false;
            for (int i = 0; i < codesArray.length(); i++) {
                if (savedCode.equalsIgnoreCase(codesArray.getString(i))) {
                    stillValid = true;
                    break;
                }
            }

            if (!stillValid) {
                // 码已从白名单移除 → 清除本地激活
                prefs.edit().clear().apply();
                return false;
            }
            return true;
        } catch (Exception e) {
            // 异常时不强制失效
            return true;
        }
    }

    /**
     * 验证授权码（首次激活）
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
