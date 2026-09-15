package com.suishou.accounting;
import android.app.*;import android.os.*;import android.webkit.*;import android.content.*;import java.io.*;
public class MainActivity extends Activity{
 WebView w;
 @Override public void onCreate(Bundle b){super.onCreate(b);w=new WebView(this);setContentView(w); WebSettings s=w.getSettings();s.setJavaScriptEnabled(true);s.setDomStorageEnabled(true);s.setAllowFileAccess(true);w.setWebViewClient(new WebViewClient());w.addJavascriptInterface(new Bridge(),"Android");w.loadUrl("file:///android_asset/html/index.html");}
 @Override public void onBackPressed(){if(w.canGoBack())w.goBack();else super.onBackPressed();}
 class Bridge{ @JavascriptInterface public void shareCsv(String text){try{File f=new File(getCacheDir(),"随手记账.csv");try(FileOutputStream o=new FileOutputStream(f)){o.write(text.getBytes("UTF-8"));} Intent i=new Intent(Intent.ACTION_SEND);i.setType("text/csv");i.putExtra(Intent.EXTRA_TEXT,text);startActivity(Intent.createChooser(i,"导出账单"));}catch(Exception e){}} }
}
