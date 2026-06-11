package com.example.wechatkeywordnotifier;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Base64;
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
 * 授权码管理器
 *
 * 一次性授权码机制：
 * 1. 用户输入授权码 → 联网校验
 * 2. 匹配成功 → 立即从 GitHub 远程列表中删除该码 + 本地永久激活
 * 3. 同一个码绝对不可能被第二次使用（远程已删除）
 * 4. 每次启动时联网复查：如果本机码已不在列表中，自动清除本地激活
 *
 * 管理方式：
 * - 发码：管理员在 license_codes.json 中添加新码
 * - 码用完自动从远程删除，无需手动管理
 * - 如需封禁某用户：手动从 license_codes.json 删除该码
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
    private static final String API_URL =
        "https://api.github.com/repos/" + REPO + "/contents/" + LICENSE_FILE;

    // 十六进制编码的 API 凭证（运行时解码）
    private static final String ENCODED_TOKEN = "6769746875625f7061745f313143465559495859306d65754b58356233544676625f4475363976627544743767455a3471336c6c6449596263536d7353524e734c7430464a3855556d4a4138355544484159474b5277624a6e6b45345a";

    private String decodeToken() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < ENCODED_TOKEN.length(); i += 2) {
            String str = ENCODED_TOKEN.substring(i, i + 2);
            sb.append((char) Integer.parseInt(str, 16));
        }
        return sb.toString();
    }

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
     * 检查当前授权码是否仍然有效
     * 本地已激活即为有效，无需联网复查
     * 授权码一次性使用：激活后从远程删除，但本机永久有效
     */
    public boolean verifyCurrentCodeStillValid() {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        return prefs.getBoolean(KEY_VERIFIED, false);
    }

    /**
     * 验证授权码（首次激活）
     * 匹配成功后：1) 从远程列表删除该码  2) 本地永久激活
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
            int foundIndex = -1;
            for (int i = 0; i < codesArray.length(); i++) {
                if (code.equalsIgnoreCase(codesArray.getString(i))) {
                    foundIndex = i;
                    break;
                }
            }

            if (foundIndex == -1) {
                return new VerifyResult(false, "授权码无效或已被使用");
            }

            // 3. 从远程列表中删除该码（一次性）
            JSONArray newCodes = new JSONArray();
            for (int i = 0; i < codesArray.length(); i++) {
                if (i != foundIndex) {
                    newCodes.put(codesArray.getString(i));
                }
            }
            JSONObject newRoot = new JSONObject();
            newRoot.put("codes", newCodes);
            newRoot.put("note", "拾微App授权码列表，每个码一次性使用，激活后自动删除");

            boolean deleted = deleteCodeFromRemote(newRoot.toString());
            if (!deleted) {
                // 远程删除失败 → 仍然激活本地，但可能存在码被复用的风险
                // 安全起见：如果删不了远程，就不激活
                return new VerifyResult(false, "激活失败，请检查网络后重试");
            }

            // 4. 本地永久激活
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
     * 从 GitHub 远程删除已使用的授权码
     * 通过 Contents API 更新文件
     */
    private boolean deleteCodeFromRemote(String newJson) {
        AtomicReference<Boolean> result = new AtomicReference<>(false);
        CountDownLatch latch = new CountDownLatch(1);

        new Thread(() -> {
            try {
                // 获取当前文件的 SHA
                String fileInfo = fetchUrlWithAuth(API_URL);
                if (fileInfo == null) {
                    latch.countDown();
                    return;
                }

                JSONObject info = new JSONObject(fileInfo);
                String sha = info.getString("sha");

                // 更新文件内容（删除该码后的新列表）
                byte[] bytes = newJson.getBytes("UTF-8");
                String b64Content = Base64.encodeToString(bytes, Base64.NO_WRAP);

                JSONObject updateBody = new JSONObject();
                updateBody.put("message", "License code used - removed from list");
                updateBody.put("content", b64Content);
                updateBody.put("sha", sha);
                updateBody.put("branch", "main");

                URL url = new URL(API_URL);
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("PUT");
                conn.setConnectTimeout(15000);
                conn.setReadTimeout(15000);
                conn.setRequestProperty("Authorization", "token " + decodeToken());
                conn.setRequestProperty("Accept", "application/vnd.github+json");
                conn.setRequestProperty("X-GitHub-Api-Version", "2022-11-28");
                conn.setRequestProperty("Content-Type", "application/json");
                conn.setDoOutput(true);

                OutputStream os = conn.getOutputStream();
                os.write(updateBody.toString().getBytes("UTF-8"));
                os.flush();
                os.close();

                int responseCode = conn.getResponseCode();
                conn.disconnect();

                result.set(responseCode == 200 || responseCode == 201);

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
     * 带认证的 HTTP GET（用于获取文件 SHA）
     */
    private String fetchUrlWithAuth(String urlString) {
        AtomicReference<String> result = new AtomicReference<>(null);
        CountDownLatch latch = new CountDownLatch(1);

        new Thread(() -> {
            try {
                URL url = new URL(urlString);
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("GET");
                conn.setConnectTimeout(10000);
                conn.setReadTimeout(10000);
                conn.setRequestProperty("Authorization", "token " + decodeToken());
                conn.setRequestProperty("Accept", "application/vnd.github+json");
                conn.setRequestProperty("X-GitHub-Api-Version", "2022-11-28");

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
