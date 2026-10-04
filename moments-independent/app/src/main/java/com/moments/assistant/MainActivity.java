package com.moments.assistant;

import android.app.*;import android.os.*;import android.provider.Settings;import android.content.*;import android.net.Uri;import android.widget.*;import java.io.*;import java.util.*;

public class MainActivity extends Activity {
    TextView status;
    @Override public void onCreate(Bundle b){ super.onCreate(b); setContentView(R.layout.activity_main); status=findViewById(R.id.txtStatus);
        findViewById(R.id.btnAccessibility).setOnClickListener(v->startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)));
        findViewById(R.id.btnWechat).setOnClickListener(v->openWechat());
        findViewById(R.id.btnOverlay).setOnClickListener(v->{ boolean on=!Store.sp(this).getBoolean("overlay",true); Store.sp(this).edit().putBoolean("overlay",on).apply(); Toast.makeText(this,on?"悬浮栏已开启，切回微信":"悬浮栏已关闭",Toast.LENGTH_SHORT).show(); });
        findViewById(R.id.btnLatest).setOnClickListener(v->showLatest());
        findViewById(R.id.btnHistory).setOnClickListener(v->TextListActivity.open(this,"history"));
        findViewById(R.id.btnMaterials).setOnClickListener(v->TextListActivity.open(this,"materials"));
        handleShare(getIntent());
    }
    void openWechat(){ Intent i=getPackageManager().getLaunchIntentForPackage("com.tencent.mm"); if(i!=null) startActivity(i); else Toast.makeText(this,"未检测到微信",Toast.LENGTH_SHORT).show(); }
    void showLatest(){ String t=Store.latest(this); new AlertDialog.Builder(this).setTitle("最近一次克隆文案").setMessage(t.isEmpty()?"暂无记录":t).setPositiveButton("复制",(d,w)->copy(t)).setNegativeButton("关闭",null).show(); }
    void copy(String t){ if(t==null)return; ((android.content.ClipboardManager)getSystemService(CLIPBOARD_SERVICE)).setPrimaryClip(ClipData.newPlainText("朋友圈文案",t)); Toast.makeText(this,"已复制",Toast.LENGTH_SHORT).show(); }
    @Override protected void onNewIntent(Intent i){ super.onNewIntent(i); setIntent(i); handleShare(i); }
    void handleShare(Intent i){ if(i==null)return; String a=i.getAction(); if(!Intent.ACTION_SEND.equals(a)&&!Intent.ACTION_SEND_MULTIPLE.equals(a))return; ArrayList<Uri> us=new ArrayList<>(); if(Intent.ACTION_SEND.equals(a)){ Uri u=i.getParcelableExtra(Intent.EXTRA_STREAM); if(u!=null)us.add(u);} else { ArrayList<Uri> x=i.getParcelableArrayListExtra(Intent.EXTRA_STREAM); if(x!=null)us.addAll(x);} int n=0; for(Uri u:us){ try{ File dir=new File(getFilesDir(),"materials"); dir.mkdirs(); String type=getContentResolver().getType(u); String ext=(type!=null&&type.startsWith("video"))?".mp4":".jpg"; File out=new File(dir,System.currentTimeMillis()+"_"+n+ext); try(InputStream in=getContentResolver().openInputStream(u); OutputStream os=new FileOutputStream(out)){ byte[] buf=new byte[8192]; int r; while((r=in.read(buf))>0)os.write(buf,0,r);} Store.addMaterial(this,out.getAbsolutePath()); n++; }catch(Exception e){} } if(n>0) status.setText("已接收并保存 "+n+" 个素材。可在“素材库”查看。"); }
}
