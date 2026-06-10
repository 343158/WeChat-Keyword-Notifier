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
 * App 启动后首先进入此页面，验证通过后跳转到 MainActivity
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

        // 快速检查：已授权且未过期 → 直接进入
        if (licenseManager.isVerified()) {
            enterMainActivity();
            return;
        }

        btnVerify.setOnClickListener(v -> {
            String code = etCode.getText().toString().trim();
            if (TextUtils.isEmpty(code)) {
                showError("请输入授权码");
                return;
            }

            // 显示加载状态
            btnVerify.setEnabled(false);
            btnVerify.setText("验证中...");
            tvError.setVisibility(View.GONE);
            progressBar.setVisibility(View.VISIBLE);

            // 在后台线程验证
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
