package com.suishou.accounting;

import android.app.Activity;
import android.os.Bundle;
import android.webkit.JavascriptInterface;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.content.Intent;
import android.graphics.Color;
import android.view.Window;
import android.view.WindowManager;
import java.io.File;
import java.io.FileOutputStream;

public class MainActivity extends Activity {
    WebView w;

    @Override public void onCreate(Bundle b) {
        super.onCreate(b);
        Window window = getWindow();
        window.setStatusBarColor(Color.rgb(247,250,248));
        window.setNavigationBarColor(Color.WHITE);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            window.getDecorView().setSystemUiVisibility(android.view.View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR);
        }
        // Do not draw the WebView behind the system status/navigation bars.
        window.clearFlags(WindowManager.LayoutParams.FLAG_TRANSLUCENT_STATUS);
        window.clearFlags(WindowManager.LayoutParams.FLAG_TRANSLUCENT_NAVIGATION);

        w = new WebView(this);
        w.setFitsSystemWindows(true);
        setContentView(w);
        WebSettings s = w.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setAllowFileAccess(true);
        w.setWebViewClient(new WebViewClient());
        w.addJavascriptInterface(new Bridge(), "Android");
        w.loadUrl("file:///android_asset/html/index.html");
    }

    @Override public void onBackPressed() {
        if (w.canGoBack()) w.goBack(); else super.onBackPressed();
    }

    class Bridge {
        @JavascriptInterface public void exportCsv(String text) {
            try {
                File dir = getExternalFilesDir(null);
                if (dir == null) dir = getFilesDir();
                File f = new File(dir, "随手记账-账单.csv");
                try (FileOutputStream o = new FileOutputStream(f)) {
                    o.write(text.getBytes("UTF-8"));
                }
                runOnUiThread(() -> android.widget.Toast.makeText(MainActivity.this,
                        "账单已导出：" + f.getAbsolutePath(), android.widget.Toast.LENGTH_LONG).show());
            } catch (Exception e) {
                runOnUiThread(() -> android.widget.Toast.makeText(MainActivity.this,
                        "导出失败：" + e.getMessage(), android.widget.Toast.LENGTH_LONG).show());
            }
        }

        // Compatibility with V2 HTML.
        @JavascriptInterface public void shareCsv(String text) { exportCsv(text); }
    }
}
