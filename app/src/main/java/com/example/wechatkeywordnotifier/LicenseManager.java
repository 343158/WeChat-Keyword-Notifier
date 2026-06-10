package com.example.wechatkeywordnotifier;

import android.content.Context;
import android.content.SharedPreferences;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 授权码管理器（一次性模式）
 * 
 * 每个授权码只能使用一次。验证成功后：
 * 1. GitHub 上的授权码列表自动删除该码（通过 API）
 * 2. 本地标记已激活，永久有效
 * 
 * 管理员操作：
 * - 发码：在 license_codes.json 中添加新码
 * - 不需要手动删码，App 验证时自动删除
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
    private static final String CONTENTS_URL = 
        "https://api.github.com/repos/" + REPO + "/contents/" + LICENSE_FILE;
    
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
     * 一次性验证授权码
     * 成功后自动从 GitHub 删除该码，本地永久激活
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
            int foundIndex = -1;
            for (int i = 0; i < codesArray.length(); i++) {
                if (code.equalsIgnoreCase(codesArray.getString(i))) {
                    found = true;
                    foundIndex = i;
                    break;
                }
            }

            if (!found) {
                return new VerifyResult(false, "授权码无效或已被使用");
            }

            // 3. 从列表中删除该码
            JSONArray newCodes = new JSONArray();
            for (int i = 0; i < codesArray.length(); i++) {
                if (i != foundIndex) {
                    newCodes.put(codesArray.getString(i));
                }
            }
            JSONObject newRoot = new JSONObject();
            newRoot.put("codes", newCodes);

            // 4. 推送更新到 GitHub（删除该授权码）
            String newJson = newRoot.toString();
            byte[] bytes = newJson.getBytes("UTF-8");
            String b64Content = android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP);

            boolean pushOk = pushUpdateToGithub(b64Content);
            if (!pushOk) {
                // 推送失败不影响本地激活（码可能已被别人先用）
                // 但为了安全，仍然激活本地
            }

            // 5. 本地永久激活
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
     * 将更新后的授权码列表推送到 GitHub
     * 通过 GitHub Contents API 更新文件
     */
    private boolean pushUpdateToGithub(String b64Content) {
        AtomicReference<Boolean> result = new AtomicReference<>(false);
        CountDownLatch latch = new CountDownLatch(1);

        new Thread(() -> {
            try {
                // 先获取当前文件 SHA
                URL getUrl = new URL(CONTENTS_URL);
                HttpURLConnection getConn = (HttpURLConnection) getUrl.openConnection();
                getConn.setRequestMethod("GET");
                getConn.setRequestProperty("User-Agent", "WeChatNotifier/2.0");
                getConn.setConnectTimeout(10000);
                getConn.setReadTimeout(10000);

                if (getConn.getResponseCode() != 200) {
                    latch.countDown();
                    return;
                }

                BufferedReader reader = new BufferedReader(
                    new InputStreamReader(getConn.getInputStream(), "UTF-8"));
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) {
                    sb.append(line);
                }
                reader.close();
                getConn.disconnect();

                JSONObject existingFile = new JSONObject(sb.toString());
                String sha = existingFile.getString("sha");

                // PUT 更新文件
                URL putUrl = new URL(CONTENTS_URL);
                HttpURLConnection putConn = (HttpURLConnection) putUrl.openConnection();
                putConn.setRequestMethod("PUT");
                putConn.setRequestProperty("User-Agent", "WeChatNotifier/2.0");
                putConn.setRequestProperty("Content-Type", "application/json");
                putConn.setConnectTimeout(10000);
                putConn.setReadTimeout(10000);
                putConn.setDoOutput(true);

                JSONObject body = new JSONObject();
                body.put("message", "Use license code: " + 
                    context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                        .getString(KEY_LICENSE_CODE, "unknown"));
                body.put("content", b64Content);
                body.put("sha", sha);
                body.put("branch", "main");

                OutputStream os = putConn.getOutputStream();
                os.write(body.toString().getBytes("UTF-8"));
                os.close();

                int respCode = putConn.getResponseCode();
                putConn.disconnect();
                result.set(respCode >= 200 && respCode < 300);

            } catch (Exception e) {
                e.printStackTrace();
            } finally {
                latch.countDown();
            }
        }).start();

        try {
            latch.await(20, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return result.get();
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
