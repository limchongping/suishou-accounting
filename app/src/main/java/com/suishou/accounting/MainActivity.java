package com.suishou.accounting;

import android.app.Activity;
import android.os.Bundle;
import android.os.Build;
import android.net.Uri;
import android.webkit.JavascriptInterface;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.webkit.WebResourceRequest;
import android.graphics.Color;
import android.view.Window;
import android.view.WindowManager;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.ByteArrayOutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Iterator;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import android.widget.Toast;
import org.json.JSONObject;

public class MainActivity extends Activity {
    private WebView w;
    private final ExecutorService networkExecutor = Executors.newCachedThreadPool();

    @Override public void onCreate(Bundle b) {
        super.onCreate(b);
        Window window = getWindow();
        window.setStatusBarColor(Color.rgb(245,247,252));
        window.setNavigationBarColor(Color.WHITE);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            window.getDecorView().setSystemUiVisibility(android.view.View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR);
        }
        window.clearFlags(WindowManager.LayoutParams.FLAG_TRANSLUCENT_STATUS);
        window.clearFlags(WindowManager.LayoutParams.FLAG_TRANSLUCENT_NAVIGATION);

        w = new WebView(this);
        w.setFitsSystemWindows(true);
        w.setBackgroundColor(Color.rgb(245,247,252));
        setContentView(w);

        WebSettings s = w.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setAllowFileAccess(true);
        s.setAllowContentAccess(false);
        s.setAllowFileAccessFromFileURLs(false);
        s.setAllowUniversalAccessFromFileURLs(false);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);

        w.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                Uri uri = request.getUrl();
                return !isBundledAppPage(uri);
            }

            @SuppressWarnings("deprecation")
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, String url) {
                return !isBundledAppPage(Uri.parse(url));
            }
        });

        // Keep the original bridge used by the previous app shell.
        w.addJavascriptInterface(new Bridge(), "Android");
        // Native HTTPS transport used by the AI copywriter HTML.
        w.addJavascriptInterface(new NativeApiBridge(), "AndroidApi");
        w.loadUrl("file:///android_asset/html/index.html");
    }

    private boolean isBundledAppPage(Uri uri) {
        return uri != null
                && "file".equalsIgnoreCase(uri.getScheme())
                && uri.getPath() != null
                && uri.getPath().startsWith("/android_asset/");
    }

    private class NativeApiBridge {
        @JavascriptInterface
        public void request(String requestJson, String callbackId) {
            networkExecutor.execute(() -> performNativeRequest(requestJson, callbackId));
        }
    }

    private void performNativeRequest(String requestJson, String callbackId) {
        int status = 0;
        String responseBody = "";
        HttpURLConnection connection = null;

        try {
            JSONObject request = new JSONObject(requestJson);
            String endpoint = request.optString("url", "");
            JSONObject options = request.optJSONObject("options");
            if (options == null) options = new JSONObject();

            URI uri = URI.create(endpoint);
            if (!"https".equalsIgnoreCase(uri.getScheme())
                    || uri.getHost() == null
                    || uri.getUserInfo() != null) {
                throw new IllegalArgumentException("为保护 API Key，API 地址必须是有效的 HTTPS 地址。");
            }

            String method = options.optString("method", "POST");
            if (!"POST".equalsIgnoreCase(method)) {
                throw new IllegalArgumentException("当前仅支持 POST 模型接口请求。");
            }

            connection = (HttpURLConnection) new URL(endpoint).openConnection();
            connection.setRequestMethod("POST");
            connection.setConnectTimeout(30000);
            connection.setReadTimeout(100000);
            connection.setUseCaches(false);
            connection.setInstanceFollowRedirects(false);
            connection.setDoInput(true);

            JSONObject headers = options.optJSONObject("headers");
            if (headers != null) {
                Iterator<String> keys = headers.keys();
                while (keys.hasNext()) {
                    String key = keys.next();
                    if (!"host".equalsIgnoreCase(key)
                            && !"content-length".equalsIgnoreCase(key)
                            && !"connection".equalsIgnoreCase(key)
                            && !"transfer-encoding".equalsIgnoreCase(key)) {
                        connection.setRequestProperty(key, headers.optString(key));
                    }
                }
            }

            String body = options.optString("body", "");
            byte[] payload = body.getBytes(StandardCharsets.UTF_8);
            connection.setDoOutput(true);
            connection.setFixedLengthStreamingMode(payload.length);
            try (OutputStream output = connection.getOutputStream()) {
                output.write(payload);
                output.flush();
            }

            status = connection.getResponseCode();
            InputStream input = status >= 400 ? connection.getErrorStream() : connection.getInputStream();
            responseBody = readFully(input);
        } catch (Exception exception) {
            status = 0;
            try {
                JSONObject error = new JSONObject();
                JSONObject detail = new JSONObject();
                detail.put("message", exception.getMessage() == null ? "安卓网络请求失败" : exception.getMessage());
                error.put("error", detail);
                responseBody = error.toString();
            } catch (Exception ignored) {
                responseBody = "{\"error\":{\"message\":\"安卓网络请求失败\"}}";
            }
        } finally {
            if (connection != null) connection.disconnect();
        }

        final int finalStatus = status;
        final String finalBody = responseBody;
        runOnUiThread(() -> {
            if (w == null) return;
            String safeId = JSONObject.quote(callbackId == null ? "" : callbackId);
            String safeBody = JSONObject.quote(finalBody == null ? "" : finalBody);
            String script = "window.__nativeFetchResult && window.__nativeFetchResult("
                    + safeId + "," + finalStatus + "," + safeBody + ");";
            w.evaluateJavascript(script, null);
        });
    }

    private String readFully(InputStream input) throws Exception {
        if (input == null) return "";
        try (InputStream in = input; ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int count;
            int total = 0;
            while ((count = in.read(buffer)) != -1) {
                total += count;
                if (total > 2 * 1024 * 1024) {
                    throw new IllegalStateException("模型接口响应超过 2 MB，已停止读取。");
                }
                out.write(buffer, 0, count);
            }
            return out.toString("UTF-8");
        }
    }

    @Override public void onBackPressed() {
        if (w != null && w.canGoBack()) w.goBack();
        else super.onBackPressed();
    }

    @Override protected void onDestroy() {
        if (w != null) {
            w.removeJavascriptInterface("AndroidApi");
            w.removeJavascriptInterface("Android");
            w.destroy();
            w = null;
        }
        networkExecutor.shutdownNow();
        super.onDestroy();
    }

    // Retain the original CSV export bridge for compatibility with the previous WebView shell.
    class Bridge {
        @JavascriptInterface public void exportCsv(String text) {
            try {
                File dir = getExternalFilesDir(null);
                if (dir == null) dir = getFilesDir();
                File f = new File(dir, "随手记账-账单.csv");
                try (FileOutputStream o = new FileOutputStream(f)) {
                    o.write(text.getBytes("UTF-8"));
                }
                runOnUiThread(() -> Toast.makeText(MainActivity.this,
                        "文件已导出：" + f.getAbsolutePath(), Toast.LENGTH_LONG).show());
            } catch (Exception e) {
                runOnUiThread(() -> Toast.makeText(MainActivity.this,
                        "导出失败：" + e.getMessage(), Toast.LENGTH_LONG).show());
            }
        }

        @JavascriptInterface public void shareCsv(String text) { exportCsv(text); }
    }
}
