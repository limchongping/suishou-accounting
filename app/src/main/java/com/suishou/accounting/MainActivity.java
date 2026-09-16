package com.suishou.notes;

import android.app.Activity;
import android.os.Bundle;
import android.os.Build;
import android.webkit.JavascriptInterface;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.view.View;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.widget.Toast;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

public class MainActivity extends Activity {
    private static final int REQ_EXPORT = 201;
    private static final int REQ_IMPORT = 202;
    private WebView webView;
    private String pendingExportText = "";

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        Window window = getWindow();
        window.clearFlags(WindowManager.LayoutParams.FLAG_TRANSLUCENT_STATUS);
        window.clearFlags(WindowManager.LayoutParams.FLAG_TRANSLUCENT_NAVIGATION);
        window.setStatusBarColor(Color.rgb(247, 249, 248));
        window.setNavigationBarColor(Color.rgb(247, 249, 248));
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            window.getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR);
        }

        webView = new WebView(this);
        webView.setBackgroundColor(Color.rgb(247, 249, 248));
        setContentView(webView);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            webView.setOnApplyWindowInsetsListener((v, insets) -> {
                int left = 0, top = 0, right = 0, bottom = 0;
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    android.graphics.Insets bars = insets.getInsets(WindowInsets.Type.systemBars());
                    left = bars.left;
                    top = bars.top;
                    right = bars.right;
                    bottom = bars.bottom;
                } else {
                    left = insets.getSystemWindowInsetLeft();
                    top = insets.getSystemWindowInsetTop();
                    right = insets.getSystemWindowInsetRight();
                    bottom = insets.getSystemWindowInsetBottom();
                }
                v.setPadding(left, top, right, bottom);
                return insets;
            });
        }

        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);
        settings.setAllowFileAccess(true);
        settings.setAllowContentAccess(true);
        settings.setBuiltInZoomControls(false);
        settings.setDisplayZoomControls(false);
        settings.setTextZoom(100);

        webView.setWebViewClient(new WebViewClient());
        webView.addJavascriptInterface(new Bridge(), "Android");
        webView.loadUrl("file:///android_asset/html/index.html");
    }

    @Override
    public void onBackPressed() {
        webView.evaluateJavascript("window.handleAndroidBack ? window.handleAndroidBack() : 'close'", value -> {
            if ("\"close\"".equals(value)) {
                MainActivity.super.onBackPressed();
            }
        });
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != RESULT_OK || data == null || data.getData() == null) return;
        Uri uri = data.getData();

        if (requestCode == REQ_EXPORT) {
            try (OutputStream out = getContentResolver().openOutputStream(uri, "w")) {
                if (out == null) throw new IllegalStateException("无法打开文件");
                out.write(pendingExportText.getBytes(StandardCharsets.UTF_8));
                out.flush();
                Toast.makeText(this, "备份已保存", Toast.LENGTH_SHORT).show();
            } catch (Exception e) {
                Toast.makeText(this, "备份失败：" + e.getMessage(), Toast.LENGTH_LONG).show();
            }
            pendingExportText = "";
        } else if (requestCode == REQ_IMPORT) {
            try (InputStream in = getContentResolver().openInputStream(uri);
                 BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
                StringBuilder builder = new StringBuilder();
                char[] buffer = new char[4096];
                int n;
                while ((n = reader.read(buffer)) > 0) builder.append(buffer, 0, n);
                String js = "window.importBackupFromAndroid(" + JSONObject.quote(builder.toString()) + ")";
                webView.evaluateJavascript(js, null);
            } catch (Exception e) {
                Toast.makeText(this, "恢复失败：" + e.getMessage(), Toast.LENGTH_LONG).show();
            }
        }
    }

    public class Bridge {
        @JavascriptInterface
        public void exportBackup(String text) {
            pendingExportText = text == null ? "" : text;
            runOnUiThread(() -> {
                Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
                intent.addCategory(Intent.CATEGORY_OPENABLE);
                intent.setType("application/json");
                intent.putExtra(Intent.EXTRA_TITLE, "随手笔记-备份.json");
                startActivityForResult(intent, REQ_EXPORT);
            });
        }

        @JavascriptInterface
        public void importBackup() {
            runOnUiThread(() -> {
                Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                intent.addCategory(Intent.CATEGORY_OPENABLE);
                intent.setType("application/json");
                startActivityForResult(intent, REQ_IMPORT);
            });
        }

        @JavascriptInterface
        public void toast(String message) {
            runOnUiThread(() -> Toast.makeText(MainActivity.this, message, Toast.LENGTH_SHORT).show());
        }
    }
}
