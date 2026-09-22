package com.example.momentsclone;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Typeface;
import android.os.Bundle;
import android.provider.Settings;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class MainActivity extends Activity {
    private LinearLayout root;
    private TextView status;
    private LinearLayout listBox;
    private DraftRepository repo;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        repo=new DraftRepository(this);
        buildUi();
    }

    @Override protected void onResume() {
        super.onResume();
        refresh();
    }

    private void buildUi() {
        ScrollView scroll=new ScrollView(this);
        root=new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(18),dp(28),dp(18),dp(40));
        scroll.addView(root,new ScrollView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView title=new TextView(this);
        title.setText("朋友圈克隆助手 V1");
        title.setTextSize(26);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        root.addView(title);

        TextView desc=new TextView(this);
        desc.setText("独立 App，不注入微信。采集当前朋友圈页面的可见文案和屏幕截图，支持连续加入批量草稿队列；发布时辅助填充文案，最终发表由你确认。\n\n仅复制你有权使用的内容；不包含隐身、规避检测或绕过平台限制。");
        desc.setTextSize(15);
        desc.setPadding(0,dp(8),0,dp(14));
        root.addView(desc);

        status=new TextView(this);
        status.setTextSize(15);
        status.setPadding(dp(10),dp(10),dp(10),dp(10));
        root.addView(status);

        Button p=button("① 打开无障碍权限");
        p.setOnClickListener(v->startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)));

        Button b=button("② 显示/隐藏悬浮采集球");
        b.setOnClickListener(v->{
            CloneAccessibilityService s=CloneAccessibilityService.getInstance();
            if(s==null) Toast.makeText(this,"请先开启无障碍服务",Toast.LENGTH_LONG).show();
            else { s.toggleBubble(); refresh(); }
        });

        Button w=button("③ 打开微信");
        w.setOnClickListener(v->openWeChat());

        Button pub=button("发布队列第一条（辅助填充）");
        pub.setOnClickListener(v->prepareFirst());

        Button done=button("当前第一条已发布 → 从队列移除");
        done.setOnClickListener(v->{
            Draft d=repo.first();
            if(d!=null){ repo.remove(d.id); repo.setPendingId(""); }
            CloneAccessibilityService s=CloneAccessibilityService.getInstance();
            if(s!=null) s.setPublishMode(false);
            refresh();
        });

        Button clear=button("清空全部草稿");
        clear.setOnClickListener(v->{ repo.clear(); refresh(); });

        TextView q=new TextView(this);
        q.setText("本地草稿队列");
        q.setTextSize(20);
        q.setTypeface(Typeface.DEFAULT_BOLD);
        q.setPadding(0,dp(22),0,dp(8));
        root.addView(q);

        listBox=new LinearLayout(this);
        listBox.setOrientation(LinearLayout.VERTICAL);
        root.addView(listBox);
        setContentView(scroll);
    }

    private Button button(String t) {
        Button b=new Button(this);
        b.setText(t);
        b.setAllCaps(false);
        LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,dp(52));
        lp.topMargin=dp(8);
        root.addView(b,lp);
        return b;
    }

    private void prepareFirst() {
        Draft d=repo.first();
        if(d==null){ Toast.makeText(this,"队列为空",Toast.LENGTH_SHORT).show(); return; }
        ClipboardManager cm=(ClipboardManager)getSystemService(Context.CLIPBOARD_SERVICE);
        cm.setPrimaryClip(ClipData.newPlainText("朋友圈文案",d.text));
        repo.setPendingId(d.id);
        CloneAccessibilityService s=CloneAccessibilityService.getInstance();
        if(s!=null) s.setPublishMode(true);
        openWeChat();
        Toast.makeText(this,"进入朋友圈发布编辑页后点悬浮“填充”；最终请手动确认发表。",Toast.LENGTH_LONG).show();
    }

    private void openWeChat() {
        Intent i=getPackageManager().getLaunchIntentForPackage("com.tencent.mm");
        if(i==null) Toast.makeText(this,"未检测到微信",Toast.LENGTH_LONG).show();
        else { i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK); startActivity(i); }
    }

    private void refresh() {
        List<Draft> ds=repo.all();
        status.setText("无障碍："+(CloneAccessibilityService.getInstance()!=null?"已连接":"未连接")+"    草稿："+ds.size()+"\n在微信目标朋友圈页点悬浮“采集”；连续采集即可形成批量队列。");
        listBox.removeAllViews();
        if(ds.isEmpty()){
            TextView e=new TextView(this);
            e.setText("暂无草稿");
            e.setTextSize(15);
            listBox.addView(e);
            return;
        }
        SimpleDateFormat f=new SimpleDateFormat("MM-dd HH:mm:ss",Locale.getDefault());
        int n=1;
        for(Draft d:ds){
            TextView v=new TextView(this);
            String p=d.text.replace('\n',' ').trim();
            if(p.length()>120) p=p.substring(0,120)+"…";
            v.setText((n++)+". "+f.format(new Date(d.createdAt))+"\n"+(p.isEmpty()?"[未识别到文案]":p)+(d.screenshotUri.isEmpty()?"":"\n截图："+d.screenshotUri));
            v.setTextSize(14);
            v.setPadding(dp(10),dp(10),dp(10),dp(10));
            listBox.addView(v);
        }
    }

    private int dp(int v){ return Math.round(v*getResources().getDisplayMetrics().density); }
}
