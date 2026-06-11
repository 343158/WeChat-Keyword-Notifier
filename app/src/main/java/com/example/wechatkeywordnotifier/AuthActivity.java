package com.example.wechatkeywordnotifier;

import android.content.Intent;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;

/**
 * 授权码验证页面
 * App 启动后首先进入此页面
 *
 * 逻辑：
 * 1. 未激活 → 显示授权码输入界面
 * 2. 已激活 → 联网检查码是否仍在白名单
 *    - 码仍在列表 → 直接进入主界面
 *    - 码已被移除 → 清除本地激活，要求重新输入
 */
public class AuthActivity extends AppCompatActivity {

    private EditText etCode;
    private Button btnVerify;
    private TextView tvError;
    private ProgressBar progressBar;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_auth);

        etCode = findViewById(R.id.etCode);
        btnVerify = findViewById(R.id.btnVerify);
        tvError = findViewById(R.id.tvError);
        progressBar = findViewById(R.id.progressBar);

        final LicenseManager licenseManager = new LicenseManager(this);

        if (licenseManager.isVerified()) {
            // 已激活 → 联网复查码是否仍然有效
            btnVerify.setEnabled(false);
            btnVerify.setText("验证中...");
            progressBar.setVisibility(View.VISIBLE);

            new Thread(() -> {
                final boolean stillValid = licenseManager.verifyCurrentCodeStillValid();

                runOnUiThread(() -> {
                    progressBar.setVisibility(View.GONE);
                    if (stillValid) {
                        enterMainActivity();
                    } else {
                        // 码已作废，显示输入界面
                        btnVerify.setEnabled(true);
                        btnVerify.setText("验 证");
                        showError("您的授权码已失效，请重新输入新授权码");
                    }
                });
            }).start();
            return;
        }

        // 未激活 → 显示输入界面
        btnVerify.setOnClickListener(v -> {
            String code = etCode.getText().toString().trim();
            if (TextUtils.isEmpty(code)) {
                showError("请输入授权码");
                return;
            }

            btnVerify.setEnabled(false);
            btnVerify.setText("验证中...");
            tvError.setVisibility(View.GONE);
            progressBar.setVisibility(View.VISIBLE);

            new Thread(() -> {
                LicenseManager.VerifyResult result = licenseManager.verifyOnline(code);

                runOnUiThread(() -> {
                    progressBar.setVisibility(View.GONE);
                    btnVerify.setEnabled(true);
                    btnVerify.setText("验 证");

                    if (result.success) {
                        Toast.makeText(AuthActivity.this, "✅ " + result.message, Toast.LENGTH_SHORT).show();
                        enterMainActivity();
                    } else {
                        showError(result.message);
                    }
                });
            }).start();
        });
    }

    private void enterMainActivity() {
        startActivity(new Intent(this, MainActivity.class));
        finish();
    }

    private void showError(String msg) {
        tvError.setText(msg);
        tvError.setVisibility(View.VISIBLE);
    }
}
