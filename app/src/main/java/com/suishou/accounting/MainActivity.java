package com.suishou.notes;

import android.os.Bundle;
import android.os.Build;
import android.webkit.JavascriptInterface;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.net.Uri;
import android.provider.OpenableColumns;
import android.database.Cursor;
import android.util.Base64;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.Toast;

import androidx.fragment.app.FragmentActivity;
import androidx.core.content.FileProvider;
import androidx.core.content.ContextCompat;
import androidx.biometric.BiometricManager;
import androidx.biometric.BiometricPrompt;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.util.Locale;
import java.util.concurrent.Executor;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.UserNotAuthenticatedException;
import javax.crypto.spec.GCMParameterSpec;

import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;

public class MainActivity extends FragmentActivity {
    private static final int REQ_EXPORT = 201;
    private static final int REQ_IMPORT = 202;
    private static final int REQ_IMAGE = 203;
    private static final int REQ_FILE = 204;
    private static final long MAX_ATTACHMENT = 12L * 1024L * 1024L;
    private static final String KEY_ALIAS = "suishou_notes_v25_aes";
    private static final String PREFS = "suishou_security";
    private static final String PREF_APP_LOCK = "app_lock";

    private WebView webView;
    private String pendingExportText = "";
    private boolean appLoaded = false;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        applySystemTheme(false);

        webView = new WebView(this);
        webView.setBackgroundColor(Color.rgb(247, 249, 248));
        reserveTopStatusBarSpace();
        setContentView(webView);

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

        if (getAppLockEnabled() && biometricAvailable()) {
            webView.setVisibility(View.INVISIBLE);
            promptAuthentication(false);
        } else {
            loadApp();
        }
    }

    private void reserveTopStatusBarSpace() {
        int res = getResources().getIdentifier("status_bar_height", "dimen", "android");
        int top = res > 0 ? getResources().getDimensionPixelSize(res) : dp(28);
        webView.setPadding(0, top, 0, 0);
        webView.setClipToPadding(false);
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private void loadApp() {
        if (appLoaded) return;
        appLoaded = true;
        webView.setVisibility(View.VISIBLE);
        webView.loadUrl("file:///android_asset/html/index.html");
    }

    private SharedPreferences prefs() {
        return getSharedPreferences(PREFS, MODE_PRIVATE);
    }

    private boolean getAppLockEnabled() {
        return prefs().getBoolean(PREF_APP_LOCK, false);
    }

    private void setAppLockEnabled(boolean enabled) {
        prefs().edit().putBoolean(PREF_APP_LOCK, enabled).apply();
    }

    private boolean biometricAvailable() {
        try {
            int auth = BiometricManager.Authenticators.BIOMETRIC_STRONG | BiometricManager.Authenticators.DEVICE_CREDENTIAL;
            return BiometricManager.from(this).canAuthenticate(auth) == BiometricManager.BIOMETRIC_SUCCESS;
        } catch (Exception e) {
            return false;
        }
    }

    private void promptAuthentication(final boolean notifyJs) {
        try {
            ensureAesKey();
            Executor executor = ContextCompat.getMainExecutor(this);
            BiometricPrompt prompt = new BiometricPrompt(this, executor, new BiometricPrompt.AuthenticationCallback() {
                @Override public void onAuthenticationSucceeded(BiometricPrompt.AuthenticationResult result) {
                    super.onAuthenticationSucceeded(result);
                    if (notifyJs) notifyVault(true); else loadApp();
                }
                @Override public void onAuthenticationError(int errorCode, CharSequence errString) {
                    super.onAuthenticationError(errorCode, errString);
                    if (notifyJs) notifyVault(false);
                    else {
                        Toast.makeText(MainActivity.this, "身份验证已取消", Toast.LENGTH_SHORT).show();
                        finish();
                    }
                }
                @Override public void onAuthenticationFailed() {
                    super.onAuthenticationFailed();
                }
            });
            BiometricPrompt.PromptInfo.Builder b = new BiometricPrompt.PromptInfo.Builder()
                    .setTitle("解锁随手笔记")
                    .setSubtitle("验证身份后继续")
                    .setConfirmationRequired(false);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                b.setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG | BiometricManager.Authenticators.DEVICE_CREDENTIAL);
            } else {
                b.setDeviceCredentialAllowed(true);
            }
            prompt.authenticate(b.build());
        } catch (Exception e) {
            if (notifyJs) notifyVault(false);
            else loadApp();
        }
    }

    private void notifyVault(boolean ok) {
        runOnUiThread(() -> {
            if (webView != null) webView.evaluateJavascript("window.onVaultUnlocked && window.onVaultUnlocked(" + (ok ? "true" : "false") + ")", null);
        });
    }

    private SecretKey ensureAesKey() throws Exception {
        KeyStore ks = KeyStore.getInstance("AndroidKeyStore");
        ks.load(null);
        if (ks.containsAlias(KEY_ALIAS)) return (SecretKey) ks.getKey(KEY_ALIAS, null);

        KeyGenerator kg = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
        KeyGenParameterSpec.Builder builder = new KeyGenParameterSpec.Builder(
                KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setUserAuthenticationRequired(true);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            builder.setUserAuthenticationParameters(3600,
                    KeyProperties.AUTH_BIOMETRIC_STRONG | KeyProperties.AUTH_DEVICE_CREDENTIAL);
        } else {
            builder.setUserAuthenticationValidityDurationSeconds(3600);
        }
        kg.init(builder.build());
        return kg.generateKey();
    }

    private String encryptTextNative(String text) {
        try {
            SecretKey key = ensureAesKey();
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key);
            byte[] encrypted = cipher.doFinal((text == null ? "" : text).getBytes(StandardCharsets.UTF_8));
            String iv = Base64.encodeToString(cipher.getIV(), Base64.NO_WRAP);
            String data = Base64.encodeToString(encrypted, Base64.NO_WRAP);
            return "v1." + iv + "." + data;
        } catch (UserNotAuthenticatedException e) {
            return "__AUTH__";
        } catch (Exception e) {
            return "";
        }
    }

    private String decryptTextNative(String packed) {
        try {
            if (packed == null || !packed.startsWith("v1.")) return "";
            String[] parts = packed.split("\\.", 3);
            if (parts.length != 3) return "";
            SecretKey key = ensureAesKey();
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            GCMParameterSpec spec = new GCMParameterSpec(128, Base64.decode(parts[1], Base64.NO_WRAP));
            cipher.init(Cipher.DECRYPT_MODE, key, spec);
            byte[] plain = cipher.doFinal(Base64.decode(parts[2], Base64.NO_WRAP));
            return new String(plain, StandardCharsets.UTF_8);
        } catch (UserNotAuthenticatedException e) {
            return "__AUTH__";
        } catch (Exception e) {
            return "";
        }
    }

    private void applySystemTheme(boolean dark) {
        Window window = getWindow();
        window.clearFlags(WindowManager.LayoutParams.FLAG_TRANSLUCENT_STATUS);
        window.clearFlags(WindowManager.LayoutParams.FLAG_TRANSLUCENT_NAVIGATION);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.setDecorFitsSystemWindows(true);
        }
        int bg = dark ? Color.rgb(16, 21, 19) : Color.rgb(247, 249, 248);
        window.setStatusBarColor(bg);
        window.setNavigationBarColor(bg);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            int flags = window.getDecorView().getSystemUiVisibility();
            if (dark) flags &= ~View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
            else flags |= View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                if (dark) flags &= ~View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
                else flags |= View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
            }
            window.getDecorView().setSystemUiVisibility(flags);
        }
        if (webView != null) webView.setBackgroundColor(bg);
    }

    @Override
    public void onBackPressed() {
        if (webView == null || !appLoaded) { super.onBackPressed(); return; }
        webView.evaluateJavascript("window.handleAndroidBack ? window.handleAndroidBack() : 'close'", value -> {
            if ("\"close\"".equals(value)) MainActivity.super.onBackPressed();
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
                char[] buffer = new char[8192]; int n;
                while ((n = reader.read(buffer)) > 0) builder.append(buffer, 0, n);
                webView.evaluateJavascript("window.importBackupFromAndroid(" + JSONObject.quote(builder.toString()) + ")", null);
            } catch (Exception e) {
                Toast.makeText(this, "恢复失败：" + e.getMessage(), Toast.LENGTH_LONG).show();
            }
        } else if (requestCode == REQ_IMAGE || requestCode == REQ_FILE) {
            try {
                JSONObject meta = saveAttachment(uri, requestCode == REQ_IMAGE);
                webView.evaluateJavascript("window.attachmentPicked(" + JSONObject.quote(meta.toString()) + ")", null);
            } catch (Exception e) {
                Toast.makeText(this, "附件添加失败：" + e.getMessage(), Toast.LENGTH_LONG).show();
            }
        }
    }

    private File attachmentDir() {
        File dir = new File(getFilesDir(), "attachments");
        if (!dir.exists()) dir.mkdirs();
        return dir;
    }

    private String displayName(Uri uri) {
        String name = "附件";
        try (Cursor c = getContentResolver().query(uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (c != null && c.moveToFirst()) { int i = c.getColumnIndex(OpenableColumns.DISPLAY_NAME); if (i >= 0 && c.getString(i) != null) name = c.getString(i); }
        } catch (Exception ignored) {}
        return name;
    }

    private long contentSize(Uri uri) {
        long size = -1;
        try (Cursor c = getContentResolver().query(uri, new String[]{OpenableColumns.SIZE}, null, null, null)) {
            if (c != null && c.moveToFirst()) { int i = c.getColumnIndex(OpenableColumns.SIZE); if (i >= 0 && !c.isNull(i)) size = c.getLong(i); }
        } catch (Exception ignored) {}
        return size;
    }

    private String extensionFromName(String name) {
        int p = name == null ? -1 : name.lastIndexOf('.');
        if (p >= 0 && p < name.length() - 1) { String ext = name.substring(p).replaceAll("[^A-Za-z0-9.]", ""); if (ext.length() <= 10) return ext; }
        return "";
    }

    private JSONObject saveAttachment(Uri uri, boolean image) throws Exception {
        long declared = contentSize(uri);
        if (declared > MAX_ATTACHMENT) throw new IllegalArgumentException("单个附件不能超过 12MB");
        String id = "a_" + Long.toString(System.currentTimeMillis(), 36) + "_" + Integer.toString((int)(Math.random()*1000000), 36);
        String name = displayName(uri), mime = getContentResolver().getType(uri);
        if (mime == null) mime = image ? "image/jpeg" : "application/octet-stream";
        File outFile;
        if (image) {
            BitmapFactory.Options bounds = new BitmapFactory.Options(); bounds.inJustDecodeBounds = true;
            try (InputStream in = getContentResolver().openInputStream(uri)) { BitmapFactory.decodeStream(in, null, bounds); }
            int sample = 1; while (Math.max(bounds.outWidth / sample, bounds.outHeight / sample) > 1800) sample *= 2;
            BitmapFactory.Options opts = new BitmapFactory.Options(); opts.inSampleSize = sample; Bitmap bmp;
            try (InputStream in = getContentResolver().openInputStream(uri)) { bmp = BitmapFactory.decodeStream(in, null, opts); }
            if (bmp != null) {
                outFile = new File(attachmentDir(), id + ".jpg");
                try (FileOutputStream out = new FileOutputStream(outFile)) { if (!bmp.compress(Bitmap.CompressFormat.JPEG, 88, out)) throw new IllegalStateException("图片处理失败"); }
                bmp.recycle(); mime = "image/jpeg";
                if (!name.toLowerCase(Locale.ROOT).endsWith(".jpg") && !name.toLowerCase(Locale.ROOT).endsWith(".jpeg")) name = name.replaceAll("\\.[^.]+$", "") + ".jpg";
            } else { outFile = new File(attachmentDir(), id + extensionFromName(name)); copyUri(uri, outFile); }
        } else { outFile = new File(attachmentDir(), id + extensionFromName(name)); copyUri(uri, outFile); }
        if (outFile.length() > MAX_ATTACHMENT) { outFile.delete(); throw new IllegalArgumentException("单个附件不能超过 12MB"); }
        JSONObject obj = new JSONObject(); obj.put("id", id); obj.put("name", name); obj.put("mime", mime); obj.put("kind", image ? "image" : "file"); obj.put("size", outFile.length()); return obj;
    }

    private void copyUri(Uri uri, File outFile) throws Exception {
        try (InputStream in = getContentResolver().openInputStream(uri); FileOutputStream out = new FileOutputStream(outFile)) {
            if (in == null) throw new IllegalStateException("无法读取文件");
            byte[] buf = new byte[16384]; long total = 0; int n;
            while ((n = in.read(buf)) > 0) { total += n; if (total > MAX_ATTACHMENT) throw new IllegalArgumentException("单个附件不能超过 12MB"); out.write(buf, 0, n); }
            out.flush();
        } catch (Exception e) { outFile.delete(); throw e; }
    }

    private File findAttachment(String id) {
        if (id == null || id.length() < 3) return null;
        File[] files = attachmentDir().listFiles(); if (files == null) return null;
        for (File f : files) if (f.getName().equals(id) || f.getName().startsWith(id + ".")) return f;
        return null;
    }

    private String mimeFor(File f, String fallback) {
        String n = f.getName().toLowerCase(Locale.ROOT);
        if (n.endsWith(".jpg") || n.endsWith(".jpeg")) return "image/jpeg";
        if (n.endsWith(".png")) return "image/png";
        if (n.endsWith(".gif")) return "image/gif";
        if (n.endsWith(".pdf")) return "application/pdf";
        if (n.endsWith(".txt")) return "text/plain";
        return fallback == null || fallback.isEmpty() ? "application/octet-stream" : fallback;
    }

    private byte[] readFile(File f) throws Exception {
        try (FileInputStream in = new FileInputStream(f); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buf = new byte[16384]; int n; while ((n = in.read(buf)) > 0) out.write(buf, 0, n); return out.toByteArray();
        }
    }

    public class Bridge {
        @JavascriptInterface public void exportBackup(String text) {
            pendingExportText = text == null ? "" : text;
            runOnUiThread(() -> { Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT); i.addCategory(Intent.CATEGORY_OPENABLE); i.setType("application/json"); i.putExtra(Intent.EXTRA_TITLE, "随手笔记-V2.5-备份.json"); startActivityForResult(i, REQ_EXPORT); });
        }
        @JavascriptInterface public void importBackup() { runOnUiThread(() -> { Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT); i.addCategory(Intent.CATEGORY_OPENABLE); i.setType("application/json"); startActivityForResult(i, REQ_IMPORT); }); }
        @JavascriptInterface public void pickImage() { runOnUiThread(() -> { Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT); i.addCategory(Intent.CATEGORY_OPENABLE); i.setType("image/*"); startActivityForResult(i, REQ_IMAGE); }); }
        @JavascriptInterface public void pickFile() { runOnUiThread(() -> { Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT); i.addCategory(Intent.CATEGORY_OPENABLE); i.setType("*/*"); startActivityForResult(i, REQ_FILE); }); }
        @JavascriptInterface public String readAttachmentDataUrl(String id) { try { File f = findAttachment(id); if (f == null || !f.exists()) return ""; return "data:" + mimeFor(f, "image/jpeg") + ";base64," + Base64.encodeToString(readFile(f), Base64.NO_WRAP); } catch (Exception e) { return ""; } }
        @JavascriptInterface public String readAttachmentBase64(String id) { try { File f = findAttachment(id); if (f == null || !f.exists()) return ""; return Base64.encodeToString(readFile(f), Base64.NO_WRAP); } catch (Exception e) { return ""; } }
        @JavascriptInterface public boolean restoreAttachment(String id, String name, String mime, String base64) { try { if (id == null || !id.matches("[A-Za-z0-9_\\-]+")) return false; byte[] bytes = Base64.decode(base64, Base64.DEFAULT); if (bytes.length > MAX_ATTACHMENT) return false; File f = new File(attachmentDir(), id + extensionFromName(name)); try (FileOutputStream out = new FileOutputStream(f)) { out.write(bytes); } return true; } catch (Exception e) { return false; } }
        @JavascriptInterface public void deleteAttachment(String id) { try { File f = findAttachment(id); if (f != null) f.delete(); } catch (Exception ignored) {} }
        @JavascriptInterface public void openAttachment(String id, String mime, String name) { runOnUiThread(() -> { try { File f = findAttachment(id); if (f == null || !f.exists()) { Toast.makeText(MainActivity.this, "附件文件不存在", Toast.LENGTH_SHORT).show(); return; } Uri u = FileProvider.getUriForFile(MainActivity.this, getPackageName() + ".fileprovider", f); Intent i = new Intent(Intent.ACTION_VIEW); i.setDataAndType(u, mimeFor(f, mime)); i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION); startActivity(Intent.createChooser(i, "打开附件")); } catch (Exception e) { Toast.makeText(MainActivity.this, "没有可打开此附件的应用", Toast.LENGTH_SHORT).show(); } }); }
        @JavascriptInterface public String hashText(String text) { try { MessageDigest md = MessageDigest.getInstance("SHA-256"); byte[] b = md.digest((text == null ? "" : text).getBytes(StandardCharsets.UTF_8)); StringBuilder sb = new StringBuilder(); for (byte x : b) sb.append(String.format(Locale.ROOT, "%02x", x)); return sb.toString(); } catch (Exception e) { return Integer.toHexString((text == null ? "" : text).hashCode()); } }
        @JavascriptInterface public void setDarkMode(boolean dark) { runOnUiThread(() -> applySystemTheme(dark)); }
        @JavascriptInterface public boolean biometricAvailable() { return MainActivity.this.biometricAvailable(); }
        @JavascriptInterface public void unlockVault() { runOnUiThread(() -> promptAuthentication(true)); }
        @JavascriptInterface public String encryptText(String text) { return encryptTextNative(text); }
        @JavascriptInterface public String decryptText(String text) { return decryptTextNative(text); }
        @JavascriptInterface public boolean getAppLockEnabled() { return MainActivity.this.getAppLockEnabled(); }
        @JavascriptInterface public void setAppLockEnabled(boolean enabled) { MainActivity.this.setAppLockEnabled(enabled); }
        @JavascriptInterface public void toast(String message) { runOnUiThread(() -> Toast.makeText(MainActivity.this, message, Toast.LENGTH_SHORT).show()); }
    }
}
